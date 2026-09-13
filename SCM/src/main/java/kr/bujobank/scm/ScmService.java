package kr.bujobank.scm;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static kr.bujobank.scm.Database.*;

/** All state changes use the authenticated actor and a single database transaction. */
public final class ScmService {
    public final Database db;
    private final String dummyHash = Passwords.hash(Passwords.token());
    public ScmService(Database db) { this.db=db; }
    public Map<String,Object> login(String username,String password) throws Exception {
        return db.transaction(c -> {
            Map<String,Object> u=one(c,"SELECT * FROM users WHERE username=? FOR UPDATE",username);
            boolean valid=Passwords.verify(password,u==null?dummyHash:(String)u.get("password_hash"));
            if(u==null)return null;
            Timestamp locked=(Timestamp)u.get("locked_until");
            if(locked!=null&&locked.toInstant().isAfter(Instant.now()))return null;
            if(!valid){execute(c,"UPDATE users SET failed_count=failed_count+1,locked_until=CASE WHEN failed_count>=5 THEN DATE_ADD(NOW(),INTERVAL 15 MINUTE) ELSE NULL END WHERE id=?",u.get("id"));return null;}
            try {u=Access.user(c,number(u,"id"),false);}catch(Access.Denied denied){return null;}
            execute(c,"UPDATE users SET failed_count=0,locked_until=NULL WHERE id=?",u.get("id"));return u;
        });
    }
    public String save(long actorId,long authVersion,String token,String action,Map<String,String[]> params,List<ImageData> images) throws Exception {
        return db.transaction(c -> {
            Map<String,Object> actor=Access.user(c,actorId,true);
            if(number(actor,"auth_version")!=authVersion)throw new Access.Denied();
            if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("요청 정보가 만료되었습니다. 화면을 새로 열어주세요.");
            if(one(c,"SELECT token FROM action_requests WHERE token=?",token)!=null)throw new IllegalArgumentException("이미 처리한 요청입니다. 목록에서 결과를 확인하세요.");
            execute(c,"INSERT INTO action_requests(token,user_id,action) VALUES(?,?,?)",token,actorId,action);
            String next;
            switch(action){
                case "supplier": Access.requireAdmin(actor);next=supplier(c,params);break;
                case "category": Access.requireAdmin(actor);next=category(c,params);break;
                case "product": Access.requireAdmin(actor); next=product(c,params,images);break;
                case "store": Access.requireAdmin(actor); next=store(c,params);break;
                case "user": case "admin": Access.requireAdmin(actor);next=user(c,actor,params,action);break;
                case "price": Access.requireAdmin(actor);next=price(c,params);break;
                case "receipt": case "adjust": Access.requireAdmin(actor);next=inventory(c,actor,params,action);break;
                case "order": Access.requireOrder(actor);next=order(c,actor,params);break;
                case "ship": Access.requireAdmin(actor);next=ship(c,actor,params);break;
                case "cancel": next=cancel(c,actor,params);break;
                case "return": Access.requireAdmin(actor);next=returnGoods(c,actor,params);break;
                case "password": next=password(c,actor,params);break;
                default: throw new IllegalArgumentException("지원하지 않는 작업입니다.");
            }
            execute(c,"INSERT INTO audit_events(actor_id,action) VALUES(?,?)",actorId,action);
            return next;
        });
    }
    private static String raw(Map<String,String[]> p,String key){String[] values=p.get(key);return values==null||values.length==0?"":values[0];}
    public static String value(Map<String,String[]> p,String key){String[] values=p.get(key);return values==null||values.length==0?"":values[0].trim();}
    public static String text(Map<String,String[]> p,String key,int max,boolean required){String s=value(p,key);if((required&&s.isEmpty())||s.length()>max)throw new IllegalArgumentException(key+" 입력값을 확인하세요. 최대 "+max+"자입니다.");return s;}
    public static long id(Map<String,String[]> p,String key){try{long n=Long.parseLong(value(p,key));if(n<1)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("유효한 "+key+" 값을 선택하세요.");}}
    public static long optionalId(Map<String,String[]> p,String key){return value(p,key).isEmpty()?0:id(p,key);}
    public static int integer(Map<String,String[]> p,String key,int min,int max){try{int n=Integer.parseInt(value(p,key));if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException(key+" 값은 "+min+"~"+max+" 범위의 정수여야 합니다.");}}
    public static BigDecimal amount(Map<String,String[]> p,String key){try{BigDecimal n=new BigDecimal(value(p,key)).setScale(2,RoundingMode.UNNECESSARY);if(n.signum()<0||n.compareTo(new BigDecimal("999999999999.99"))>0)throw new NumberFormatException();return n;}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException(key+" 금액을 소수점 둘째 자리 이내의 0 이상 숫자로 입력하세요.");}}
    private static String date(Map<String,String[]> p){try{return LocalDate.parse(value(p,"occurred_on")).toString();}catch(Exception e){throw new IllegalArgumentException("처리일을 선택하세요.");}}
    private static int active(Map<String,String[]> p){String s=value(p,"active");if(!s.equals("1")&&!s.equals("0"))throw new IllegalArgumentException("사용 상태를 선택하세요.");return Integer.parseInt(s);}
    private static void version(Map<String,Object> row,Map<String,String[]> p){if(row==null)throw new IllegalArgumentException("항목을 찾을 수 없습니다.");if(number(row,"version")!=integer(p,"version",0,Integer.MAX_VALUE))throw new IllegalArgumentException("다른 사용자가 변경한 정보입니다. 새로 조회한 뒤 수정하세요.");}
    private static Map<String,Object> productLock(Connection c,long id) throws SQLException {Map<String,Object> p=one(c,"SELECT * FROM products WHERE id=? FOR UPDATE",id);if(p==null)throw new IllegalArgumentException("상품을 찾을 수 없습니다.");return p;}
    private String product(Connection c,Map<String,String[]> p,List<ImageData> images)throws SQLException{
        long id=optionalId(p,"id");String code=text(p,"code",30,true);if(!code.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("상품코드는 영문, 숫자, -, _만 사용할 수 있습니다.");
        Map<String,Object> old=id==0?null:productLock(c,id); if(old!=null)version(old,p);
        Map<String,Object> selected=selectCategory(c,id(p,"category_id"),old==null?0:number(old,"category_id"));
        String name=text(p,"name",100,true),category=(String)selected.get("name"),unit=text(p,"unit",100,true),description=text(p,"description",5000,false);
        BigDecimal price=amount(p,"price");int safety=integer(p,"safety_stock",0,100000000),enabled=active(p);
        if(id==0){id=insert(c,"INSERT INTO products(code,name,category,category_id,unit,price,safety_stock,active,description) VALUES(?,?,?,?,?,?,?,?,?)",code,name,category,selected.get("id"),unit,price,safety,enabled,description);}
        else {execute(c,"UPDATE products SET code=?,name=?,category=?,category_id=?,unit=?,price=?,safety_stock=?,active=?,description=?,version=version+1 WHERE id=?",code,name,category,selected.get("id"),unit,price,safety,enabled,description,id);}
        if(!images.isEmpty()||"1".equals(value(p,"clear_images"))){execute(c,"DELETE FROM product_images WHERE product_id=?",id);int order=0;for(ImageData image:images)execute(c,"INSERT INTO product_images(product_id,mime,content,sort_no) VALUES(?,?,?,?)",id,image.mime,image.bytes,order++);}
        return "products";
    }
    public static Map<String,Object> selectCategory(Connection c,long categoryId,long originalCategoryId)throws SQLException {
        Map<String,Object> selected=one(c,"SELECT * FROM categories WHERE id=? FOR UPDATE",categoryId);
        if(selected==null||(!bool(selected,"active")&&categoryId!=originalCategoryId))throw new IllegalArgumentException("사용 중인 카테고리를 선택하세요. 카테고리 관리에서 먼저 등록할 수 있습니다.");
        return selected;
    }
    private String category(Connection c,Map<String,String[]> p)throws SQLException {
        long categoryId=optionalId(p,"id");String name=text(p,"name",60,true);
        int sort=integer(p,"sort_order",0,999999),enabled=active(p);
        if(categoryId>0)version(one(c,"SELECT * FROM categories WHERE id=? FOR UPDATE",categoryId),p);
        if(one(c,"SELECT id FROM categories WHERE name=? AND id<>?",name,categoryId)!=null)throw new IllegalArgumentException("이미 등록된 카테고리명입니다.");
        if(categoryId==0)insert(c,"INSERT INTO categories(name,sort_order,active) VALUES(?,?,?)",name,sort,enabled);
        else execute(c,"UPDATE categories SET name=?,sort_order=?,active=?,version=version+1 WHERE id=?",name,sort,enabled,categoryId);
        return "categories";
    }
    private String supplier(Connection c,Map<String,String[]> p)throws SQLException{
        long id=optionalId(p,"id");String name=text(p,"name",100,true),contact=text(p,"contact",100,false),phone=text(p,"phone",40,false),address=text(p,"address",300,false);int enabled=active(p);
        if(id>0)version(one(c,"SELECT * FROM suppliers WHERE id=? FOR UPDATE",id),p);
        if(one(c,"SELECT id FROM suppliers WHERE name=? AND id<>?",name,id)!=null)throw new IllegalArgumentException("이미 등록된 공급업체명입니다.");
        if(id==0)insert(c,"INSERT INTO suppliers(name,contact,phone,address,active) VALUES(?,?,?,?,?)",name,contact,phone,address,enabled);
        else execute(c,"UPDATE suppliers SET name=?,contact=?,phone=?,address=?,active=?,version=version+1 WHERE id=?",name,contact,phone,address,enabled,id);
        return "suppliers";
    }
    private String store(Connection c,Map<String,String[]> p)throws SQLException{
        long id=optionalId(p,"id");String code=text(p,"code",30,true),name=text(p,"name",100,true),contact=text(p,"contact",100,false),phone=text(p,"phone",40,false),address=text(p,"address",300,false);int enabled=active(p);
        if(id==0)insert(c,"INSERT INTO stores(code,name,contact,phone,address,active) VALUES(?,?,?,?,?,?)",code,name,contact,phone,address,enabled);
        else {version(one(c,"SELECT * FROM stores WHERE id=? FOR UPDATE",id),p);execute(c,"UPDATE stores SET code=?,name=?,contact=?,phone=?,address=?,active=?,version=version+1 WHERE id=?",code,name,contact,phone,address,enabled,id);}
        return "stores";
    }
    private String user(Connection c,Map<String,Object> actor,Map<String,String[]> p,String action)throws SQLException{
        boolean admin="admin".equals(action);if(admin)Access.requireSuper(actor);
        long target=optionalId(p,"id");Map<String,Object> old=null;
        if(target>0){old=one(c,"SELECT * FROM users WHERE id=? FOR UPDATE",target);version(old,p);if(Access.admin(old)!=admin)throw new Access.Denied();}
        String role=value(p,"role");if(!(admin?Arrays.asList("SUPER","ADMIN"):Arrays.asList("STORE_ORDER","STORE_VIEW")).contains(role))throw new Access.Denied();
        Long storeId=admin?null:id(p,"store_id");
        if(storeId!=null){Map<String,Object> s=one(c,"SELECT * FROM stores WHERE id=? FOR UPDATE",storeId);if(s==null||!bool(s,"active"))throw new IllegalArgumentException("사용 중인 매장을 선택하세요.");}
        int enabled=active(p);if(target==number(actor,"id")&&(!"SUPER".equals(role)||enabled==0))throw new IllegalArgumentException("현재 로그인한 최고 관리자의 권한/상태는 변경할 수 없습니다.");
        String username=text(p,"username",60,true);if(!username.matches("[A-Za-z0-9._@-]{3,60}"))throw new IllegalArgumentException("아이디는 영문, 숫자, ., _, @, -로 3~60자 입력하세요.");
        String name=text(p,"name",100,true),phone=text(p,"phone",40,false),password=raw(p,"password");String hash=old==null?null:(String)old.get("password_hash");
        if(target==0||!password.isEmpty()){Passwords.validate(password);hash=Passwords.hash(password);}
        if(target==0)insert(c,"INSERT INTO users(username,password_hash,name,phone,role,store_id,active) VALUES(?,?,?,?,?,?,?)",username,hash,name,phone,role,storeId,enabled);
        else execute(c,"UPDATE users SET username=?,password_hash=?,name=?,phone=?,role=?,store_id=?,active=?,version=version+1,auth_version=auth_version+1,failed_count=0,locked_until=NULL WHERE id=?",username,hash,name,phone,role,storeId,enabled,target);
        return admin?"admins":"users";
    }
    private String price(Connection c,Map<String,String[]> p)throws SQLException{
        long storeId=id(p,"store_id");Map<String,Object> s=one(c,"SELECT * FROM stores WHERE id=? FOR UPDATE",storeId);if(s==null||!bool(s,"active"))throw new IllegalArgumentException("사용 중인 매장을 선택하세요.");
        long productId=optionalId(p,"product_id");String mode=value(p,"mode");BigDecimal value=amount(p,"value");if(!Arrays.asList("direct","discount","markup").contains(mode))throw new IllegalArgumentException("가격 적용 방식을 선택하세요.");
        if(mode.equals("discount")&&value.compareTo(new BigDecimal("100"))>0)throw new IllegalArgumentException("할인율은 100% 이하여야 합니다.");
        List<Map<String,Object>> products=rows(c,"SELECT * FROM products "+(productId==0?"WHERE active=1":"WHERE id=?")+" ORDER BY id FOR UPDATE",productId==0?new Object[]{}:new Object[]{productId});
        if(products.isEmpty())throw new IllegalArgumentException("적용할 상품이 없습니다.");
        for(Map<String,Object> product:products){BigDecimal base=(BigDecimal)product.get("price");BigDecimal result=mode.equals("direct")?value:base.multiply(BigDecimal.ONE.add(value.divide(new BigDecimal("100")).multiply(mode.equals("discount")?BigDecimal.ONE.negate():BigDecimal.ONE))).setScale(2,RoundingMode.HALF_UP);if(result.compareTo(new BigDecimal("999999999999.99"))>0)throw new IllegalArgumentException("적용 금액이 너무 큽니다.");execute(c,"INSERT INTO store_prices(store_id,product_id,price) VALUES(?,?,?) ON DUPLICATE KEY UPDATE price=VALUES(price)",storeId,product.get("id"),result);}
        return "prices?storeId="+storeId;
    }
    private String inventory(Connection c,Map<String,Object> actor,Map<String,String[]> p,String action)throws SQLException{
        Map<String,Object> product=productLock(c,id(p,"product_id"));int before=(int)number(product,"stock");int after;
        if(action.equals("receipt")){int count=integer(p,"quantity",1,100000000);after=Math.addExact(before,count);}else{after=integer(p,"quantity",0,100000000);if(integer(p,"expected_stock",0,Integer.MAX_VALUE)!=before)throw new IllegalArgumentException("재고가 변경되었습니다. 현재 재고를 다시 조회하세요.");}
        Long supplierId=null;String supplierName="";
        if(action.equals("receipt")){
            supplierId=id(p,"supplier_id");Map<String,Object> selected=one(c,"SELECT * FROM suppliers WHERE id=? FOR UPDATE",supplierId);
            if(selected==null||!bool(selected,"active"))throw new IllegalArgumentException("사용 중인 공급업체를 선택하세요. 공급업체 관리에서 먼저 등록할 수 있습니다.");
            supplierName=(String)selected.get("name");
        }
        movement(c,product,actor,action.equals("receipt")?"입고":"재고 조정",after,date(p),text(p,"note",1000,true),supplierName,null,supplierId);
        return action.equals("receipt")?"receipts":"inventory";
    }
    private static void movement(Connection c,Map<String,Object> product,Map<String,Object> actor,String kind,int after,String date,String note,String supplier,Long order)throws SQLException{
        movement(c,product,actor,kind,after,date,note,supplier,order,null);
    }
    private static void movement(Connection c,Map<String,Object> product,Map<String,Object> actor,String kind,int after,String date,String note,String supplier,Long order,Long supplierId)throws SQLException{
        int before=(int)number(product,"stock");if(after<0||after>100000000)throw new IllegalArgumentException("재고 수량이 허용 범위를 벗어납니다.");
        execute(c,"UPDATE products SET stock=?,version=version+1 WHERE id=?",after,product.get("id"));
        execute(c,"INSERT INTO inventory_events(product_id,actor_id,kind,delta,before_stock,after_stock,occurred_on,note,supplier,order_id,supplier_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",product.get("id"),actor.get("id"),kind,after-before,before,after,date,note,supplier,order,supplierId);
    }
    private String order(Connection c,Map<String,Object> actor,Map<String,String[]> p)throws SQLException{
        String[] productIds=p.get("product_id"),quantities=p.get("quantity");if(productIds==null||quantities==null||productIds.length!=quantities.length||productIds.length<1||productIds.length>100)throw new IllegalArgumentException("1~100개 상품을 선택하세요.");
        SortedMap<Long,Integer> cart=new TreeMap<>();for(int i=0;i<productIds.length;i++){try{long product=Long.parseLong(productIds[i]);int qty=Integer.parseInt(quantities[i]);if(product<1||qty<1||qty>100000||cart.put(product,qty)!=null)throw new NumberFormatException();}catch(NumberFormatException e){throw new IllegalArgumentException("발주 상품과 수량을 확인하세요. 중복 상품은 한 줄로 합쳐주세요.");}}
        long storeId=number(actor,"store_id");Map<String,Object> store=one(c,"SELECT * FROM stores WHERE id=? FOR UPDATE",storeId);if(store==null||!bool(store,"active"))throw new Access.Denied();
        long orderId=insert(c,"INSERT INTO purchase_orders(store_id,created_by,note) VALUES(?,?,?)",storeId,actor.get("id"),text(p,"note",1000,false));int line=0;
        for(Map.Entry<Long,Integer> entry:cart.entrySet()){Map<String,Object> product=productLock(c,entry.getKey());if(!bool(product,"active"))throw new IllegalArgumentException("판매 중지된 상품이 포함되어 있습니다.");Map<String,Object> price=one(c,"SELECT price FROM store_prices WHERE store_id=? AND product_id=?",storeId,entry.getKey());execute(c,"INSERT INTO order_lines(order_id,line_no,product_id,product_name,unit,unit_price,quantity) VALUES(?,?,?,?,?,?,?)",orderId,++line,product.get("id"),product.get("name"),product.get("unit"),price==null?product.get("price"):price.get("price"),entry.getValue());}
        return "detail?id="+orderId;
    }
    private Map<String,Object> orderLock(Connection c,Map<String,Object> actor,long orderId)throws SQLException{Map<String,Object> o=one(c,"SELECT * FROM purchase_orders WHERE id=? FOR UPDATE",orderId);Access.requireStore(actor,o);return o;}
    private String ship(Connection c,Map<String,Object> actor,Map<String,String[]> p)throws SQLException{
        long orderId=id(p,"order_id");Map<String,Object> o=orderLock(c,actor,orderId);if("취소".equals(o.get("status")))throw new IllegalArgumentException("취소된 발주는 출고할 수 없습니다.");
        List<Map<String,Object>> lines=rows(c,"SELECT * FROM order_lines WHERE order_id=? ORDER BY product_id FOR UPDATE",orderId);String occurred=date(p),note=text(p,"note",1000,false);long shipment=0;
        for(Map<String,Object> line:lines){String key="qty_"+line.get("id");if(value(p,key).isEmpty())continue;int qty=integer(p,key,0,100000);if(qty==0)continue;if(qty>number(line,"quantity")-number(line,"shipped"))throw new IllegalArgumentException("미출고 수량을 초과할 수 없습니다.");Map<String,Object> product=productLock(c,number(line,"product_id"));if(qty>number(product,"stock"))throw new IllegalArgumentException(line.get("product_name")+" 재고가 부족합니다.");if(shipment==0)shipment=insert(c,"INSERT INTO shipments(order_id,actor_id,occurred_on,note) VALUES(?,?,?,?)",orderId,actor.get("id"),occurred,note);execute(c,"INSERT INTO shipment_lines(shipment_id,line_id,quantity) VALUES(?,?,?)",shipment,line.get("id"),qty);execute(c,"UPDATE order_lines SET shipped=shipped+? WHERE id=?",qty,line.get("id"));movement(c,product,actor,"출고",(int)number(product,"stock")-qty,occurred,note,"",orderId);}
        if(shipment==0)throw new IllegalArgumentException("출고할 수량을 한 개 이상 입력하세요.");
        long remaining=number(one(c,"SELECT SUM(quantity-shipped) n FROM order_lines WHERE order_id=?",orderId),"n");execute(c,"UPDATE purchase_orders SET status=? WHERE id=?",remaining==0?"출고 완료":"부분 출고",orderId);return "detail?id="+orderId;
    }
    private String cancel(Connection c,Map<String,Object> actor,Map<String,String[]> p)throws SQLException{
        if(!Access.admin(actor))Access.requireOrder(actor);long orderId=id(p,"order_id");Map<String,Object> o=orderLock(c,actor,orderId);
        if(!"접수".equals(o.get("status"))||number(one(c,"SELECT SUM(shipped) n FROM order_lines WHERE order_id=?",orderId),"n")>0)throw new IllegalArgumentException("아직 출고되지 않은 접수 상태 발주만 취소할 수 있습니다.");
        execute(c,"UPDATE purchase_orders SET status='취소',cancel_reason=?,cancelled_at=NOW() WHERE id=?",text(p,"note",1000,true),orderId);return "detail?id="+orderId;
    }
    private String returnGoods(Connection c,Map<String,Object> actor,Map<String,String[]> p)throws SQLException{
        long orderId=id(p,"order_id");orderLock(c,actor,orderId);Map<String,Object> line=one(c,"SELECT * FROM order_lines WHERE id=? AND order_id=? FOR UPDATE",id(p,"line_id"),orderId);if(line==null)throw new IllegalArgumentException("반품할 상품을 선택하세요.");int negative=integer(p,"quantity",-100000,-1),qty=-negative;if(qty>number(line,"shipped")-number(line,"returned"))throw new IllegalArgumentException("출고 수량에서 기존 반품을 제외한 수량을 초과할 수 없습니다.");String reason=text(p,"note",1000,true);boolean restock="1".equals(value(p,"restock"));
        execute(c,"INSERT INTO order_returns(line_id,quantity,restock,reason,actor_id) VALUES(?,?,?,?,?)",line.get("id"),negative,restock,reason,actor.get("id"));execute(c,"UPDATE order_lines SET returned=returned+? WHERE id=?",qty,line.get("id"));
        if(restock){Map<String,Object> product=productLock(c,number(line,"product_id"));movement(c,product,actor,"반품 입고",Math.addExact((int)number(product,"stock"),qty),LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),reason,"",orderId);}return "detail?id="+orderId;
    }
    private String password(Connection c,Map<String,Object> actor,Map<String,String[]> p)throws SQLException{
        if(!Passwords.verify(raw(p,"current_password"),(String)actor.get("password_hash")))throw new IllegalArgumentException("현재 비밀번호를 확인하세요.");String password=raw(p,"password");Passwords.validate(password);if(!password.equals(raw(p,"confirm_password")))throw new IllegalArgumentException("새 비밀번호 확인이 일치하지 않습니다.");execute(c,"UPDATE users SET password_hash=?,auth_version=auth_version+1,version=version+1 WHERE id=?",Passwords.hash(password),actor.get("id"));return "login";
    }
    public static final class ImageData {public final String mime;public final byte[] bytes;public ImageData(String mime,byte[] bytes){this.mime=mime;this.bytes=bytes;}}
}