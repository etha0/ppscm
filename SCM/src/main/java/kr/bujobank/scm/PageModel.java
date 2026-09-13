package kr.bujobank.scm;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import static kr.bujobank.scm.Database.*;

/** Read models and form definitions for server-rendered JSP pages. */
public final class PageModel {
    private final Connection c; private final HttpServletRequest req; private final Map<String,Object> actor;
    public PageModel(Connection c,HttpServletRequest req,Map<String,Object> actor){this.c=c;this.req=req;this.actor=actor;}
    private void put(String key,Object value){req.setAttribute(key,value);}
    private String param(String key){String s=req.getParameter(key);return s==null?"":s.trim();}
    private long paramId(String key){try{return Long.parseLong(param(key));}catch(Exception e){return 0;}}
    public void load(String page)throws SQLException{
        put("today",LocalDate.now(ZoneId.of("Asia/Seoul")).toString());put("actor",actor);put("storeMode",!Access.admin(actor));put("canOrder",Access.canOrder(actor));put("superuser",Access.superuser(actor));
        put("requestToken",Passwords.token());
        if(Arrays.asList("products","inventory","prices","catalog","product-form","edit").contains(page)){
            Long storeId=null; if(Access.admin(actor)){if(paramId("storeId")>0)storeId=paramId("storeId");}else{storeId=number(actor,"store_id");}
            put("selectedStore",storeId);
            put("products",products(storeId,!Access.admin(actor)));put("categories",rows(c,"SELECT c.id,c.name category,c.active FROM categories c "+(Access.admin(actor)?"":"WHERE c.active=1 OR EXISTS (SELECT 1 FROM products p WHERE p.category_id=c.id AND p.active=1) ")+"ORDER BY c.sort_order,c.name,c.id"));
        }
        switch(page){
            case "dashboard":case "reports":dashboard();break;
            case "products":case "prices":case "inventory": if(page.equals("prices"))put("stores",stores());if(page.equals("inventory"))put("events",rows(c,"SELECT e.*,p.name product_name,u.name actor_name FROM inventory_events e JOIN products p ON p.id=e.product_id JOIN users u ON u.id=e.actor_id ORDER BY e.id DESC LIMIT 100"));break;
            case "suppliers":put("columns",Arrays.asList("공급업체명","담당자","연락처","주소","상태"));put("rows",tableRows(rows(c,"SELECT id,name,contact,phone,address,CASE WHEN active=1 THEN '사용 중' ELSE '중지' END state FROM suppliers ORDER BY name,id")));put("editAction","supplier");break;
            case "categories":put("columns",Arrays.asList("카테고리명","표시 순서","연결 상품 수","상태"));put("rows",tableRows(rows(c,"SELECT c.id,c.name,c.sort_order,(SELECT COUNT(*) FROM products p WHERE p.category_id=c.id) product_count,CASE WHEN c.active=1 THEN '사용 중' ELSE '중지' END state FROM categories c ORDER BY c.sort_order,c.name,c.id")));put("editAction","category");break;
            case "stores": people("store");break;
            case "users":people("user");break;
            case "admins":people("admin");break;
            case "orders":case "shipments":case "history": orders();break;
            case "receipts":receipts();break;
            case "detail":detail(paramId("id"));break;
            case "edit":form(param("action"),paramId("id"));break;
            case "product-form":form("product",paramId("id"));break;
            case "password":form("password",0);break;
            default:break;
        }
    }
    public void inventoryHistory()throws SQLException{
        Access.requireAdmin(actor);long id=paramId("id"),before=paramId("before");
        Map<String,Object> product=one(c,"SELECT id,code,name,stock FROM products WHERE id=?",id);
        if(product==null)throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        put("historyProduct",product);
        List<Map<String,Object>> events=rows(c,"SELECT e.*,u.name actor_name FROM inventory_events e JOIN users u ON u.id=e.actor_id WHERE e.product_id=? AND (?=0 OR e.id<?) ORDER BY e.id DESC LIMIT 101",id,before,before);
        boolean more=events.size()>100;if(more)events.remove(100);
        put("historyEvents",events);put("historyMore",more);
        put("historyCursor",events.isEmpty()?0:events.get(events.size()-1).get("id"));
    }
    public List<Map<String,Object>> products(Long storeId,boolean activeOnly)throws SQLException{
        return rows(c,"SELECT p.*,cat.name category,COALESCE(sp.price,p.price) effective_price FROM products p JOIN categories cat ON cat.id=p.category_id LEFT JOIN store_prices sp ON sp.product_id=p.id AND sp.store_id=?"+(activeOnly?" WHERE p.active=1":"")+" ORDER BY p.id DESC",storeId);
    }
    private List<Map<String,Object>> stores()throws SQLException{return rows(c,"SELECT id,name,active FROM stores ORDER BY name");}
    private void people(String kind)throws SQLException{
        List<Map<String,Object>> data;
        if(kind.equals("store")){put("columns",Arrays.asList("매장코드","매장명","담당자","연락처","주소","상태"));data=rows(c,"SELECT id,code,name,contact,phone,address,CASE WHEN active=1 THEN '사용 중' ELSE '중지' END state FROM stores ORDER BY id DESC");}
        else {put("columns",Arrays.asList("아이디","사용자명","소속","권한","연락처","상태"));data=rows(c,"SELECT u.id,u.username,u.name,COALESCE(s.name,'통합 운영') store_name,CASE u.role WHEN 'SUPER' THEN '최고 관리자' WHEN 'ADMIN' THEN '운영 관리자' WHEN 'STORE_ORDER' THEN '발주 가능' ELSE '조회 전용' END role_name,u.phone,CASE WHEN u.active=1 THEN '사용 중' ELSE '중지' END state FROM users u LEFT JOIN stores s ON s.id=u.store_id WHERE u.role "+(kind.equals("admin")?"IN ('SUPER','ADMIN')":"IN ('STORE_ORDER','STORE_VIEW')")+" ORDER BY u.id DESC");}
        put("rows",tableRows(data));put("editAction",kind);
    }
    private List<Map<String,Object>> tableRows(List<Map<String,Object>> data){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> row:data){Map<String,Object> r=new HashMap<>();r.put("id",row.get("id"));List<Object> cells=new ArrayList<>();for(Map.Entry<String,Object> cell:row.entrySet())if(!cell.getKey().equals("id"))cells.add(cell.getValue());r.put("cells",cells);result.add(r);}return result;}
    private void orders()throws SQLException{
        String where=Access.admin(actor)?"":" WHERE o.store_id=?";
        put("orders",rows(c,"SELECT o.*,s.name store_name,u.name actor_name,COALESCE(SUM(l.unit_price*l.quantity),0) total,COUNT(l.id) line_count,MIN(l.product_name) first_product FROM purchase_orders o JOIN stores s ON s.id=o.store_id JOIN users u ON u.id=o.created_by LEFT JOIN order_lines l ON l.order_id=o.id"+where+" GROUP BY o.id ORDER BY o.id DESC",Access.admin(actor)?new Object[]{}:new Object[]{actor.get("store_id")}));
    }
    private void receipts()throws SQLException{put("columns",Arrays.asList("상품","수량","공급업체 (입고 당시)","처리일","등록일","담당자","비고"));put("rows",tableRows(rows(c,"SELECT e.id,p.name,e.delta,e.supplier,e.occurred_on,e.created_at,u.name actor_name,e.note FROM inventory_events e JOIN products p ON p.id=e.product_id JOIN users u ON u.id=e.actor_id WHERE e.kind='입고' ORDER BY e.id DESC")));}
    private Map<String,Object> detail(long id)throws SQLException{
        Map<String,Object> order=one(c,"SELECT o.*,s.name store_name,u.name actor_name FROM purchase_orders o JOIN stores s ON s.id=o.store_id JOIN users u ON u.id=o.created_by WHERE o.id=?",id);Access.requireStore(actor,order);
        List<Map<String,Object>> lines=rows(c,"SELECT l.*,p.stock,l.quantity-l.shipped remaining,l.unit_price*l.quantity total FROM order_lines l JOIN products p ON p.id=l.product_id WHERE l.order_id=? ORDER BY l.line_no",id);put("order",order);put("lines",lines);
        put("orderTotal",one(c,"SELECT SUM(unit_price*quantity) total FROM order_lines WHERE order_id=?",id).get("total"));
        put("shipments",rows(c,"SELECT sh.id,sh.occurred_on,sh.created_at,sh.note,u.name actor_name,l.product_name,sl.quantity FROM shipments sh JOIN shipment_lines sl ON sl.shipment_id=sh.id JOIN order_lines l ON l.id=sl.line_id JOIN users u ON u.id=sh.actor_id WHERE sh.order_id=? ORDER BY sh.id, l.line_no",id));
        put("returns",rows(c,"SELECT r.*,l.product_name,l.unit_price*r.quantity total,u.name actor_name FROM order_returns r JOIN order_lines l ON l.id=r.line_id JOIN users u ON u.id=r.actor_id WHERE l.order_id=? ORDER BY r.id DESC",id));return order;
    }
    private void dashboard()throws SQLException{
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));String from=param("from"),to=param("to");try{if(from.isEmpty())from=today.minusDays(6).toString();if(to.isEmpty())to=today.toString();if(LocalDate.parse(from).isAfter(LocalDate.parse(to)))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("통계 조회 날짜를 확인하세요.");}
        put("from",from);put("to",to);put("stores",stores());put("reportProducts",rows(c,"SELECT id,name FROM products ORDER BY name"));put("reportStore",paramId("storeId"));put("reportProduct",paramId("productId"));
        put("stats",one(c,"SELECT (SELECT COUNT(*) FROM purchase_orders WHERE DATE(created_at)=CURRENT_DATE AND status<>'취소') today_orders,(SELECT COUNT(*) FROM purchase_orders WHERE status IN ('접수','부분 출고')) waiting,(SELECT COALESCE(SUM(sl.quantity*l.unit_price),0) FROM shipments sh JOIN shipment_lines sl ON sl.shipment_id=sh.id JOIN order_lines l ON l.id=sl.line_id WHERE sh.occurred_on=CURRENT_DATE) shipped_amount,(SELECT COUNT(*) FROM products WHERE active=1 AND stock<=safety_stock) low_stock"));
        String grouping=param("group");if(!Arrays.asList("store","date","product").contains(grouping))grouping="store";put("reportGroup",grouping);
        String groupExpr=grouping.equals("date")?"DATE(o.created_at)":grouping.equals("product")?"l.product_id":"o.store_id";
        String label=grouping.equals("date")?"DATE(o.created_at)":grouping.equals("product")?"MIN(l.product_name)":"MIN(s.name)";
        String where=" WHERE o.status<>'취소' AND o.created_at>=? AND o.created_at<DATE_ADD(?,INTERVAL 1 DAY)";List<Object> args=new ArrayList<>(Arrays.asList(from,to));
        if(paramId("storeId")>0){where+=" AND o.store_id=?";args.add(paramId("storeId"));}if(paramId("productId")>0){where+=" AND l.product_id=?";args.add(paramId("productId"));}
        put("reportRows",rows(c,"SELECT "+label+" label,COUNT(DISTINCT o.id) order_count,SUM(l.quantity) quantity,SUM(l.unit_price*l.quantity) total,SUM(l.shipped) shipped,SUM(l.unit_price*l.shipped) shipped_total,SUM(l.returned) returned,SUM(l.unit_price*l.returned) returned_total FROM purchase_orders o JOIN stores s ON s.id=o.store_id JOIN order_lines l ON l.order_id=o.id"+where+" GROUP BY "+groupExpr+" ORDER BY "+groupExpr,args.toArray()));
        put("chartRows",rows(c,"SELECT DATE(o.created_at) day,COUNT(DISTINCT o.id) orders,SUM(l.quantity) quantity,SUM(l.shipped) shipped FROM purchase_orders o JOIN order_lines l ON l.order_id=o.id"+where+" GROUP BY DATE(o.created_at) ORDER BY day",args.toArray()));
        @SuppressWarnings("unchecked") List<Map<String,Object>> chart=(List<Map<String,Object>>)req.getAttribute("chartRows");long max=1;for(Map<String,Object> row:chart)max=Math.max(max,number(row,"quantity"));put("chartMax",max);
        String eventWhere=" WHERE e.occurred_on>=? AND e.occurred_on<=?";List<Object> eventArgs=new ArrayList<>(Arrays.asList(from,to));if(paramId("storeId")>0){eventWhere+=" AND o.store_id=?";eventArgs.add(paramId("storeId"));}if(paramId("productId")>0){eventWhere+=" AND e.product_id=?";eventArgs.add(paramId("productId"));}
        put("inventoryReport",rows(c,"SELECT e.occurred_on day,MIN(p.name) name,e.kind,SUM(e.delta) quantity,COUNT(*) event_count FROM inventory_events e JOIN products p ON p.id=e.product_id LEFT JOIN purchase_orders o ON o.id=e.order_id"+eventWhere+" GROUP BY e.occurred_on,e.product_id,e.kind ORDER BY e.occurred_on DESC,e.product_id",eventArgs.toArray()));orders();
    }
    public void form(String action,long id)throws SQLException{
        if(!action.equals("password"))Access.requireAdmin(actor);if(action.equals("admin"))Access.requireSuper(actor);
        List<Map<String,Object>> fields=new ArrayList<>();Map<String,Object> current=new HashMap<>();String title;
        if(id>0&&Arrays.asList("product","category","supplier","store","user","admin").contains(action)){String table=action.equals("product")?"products":action.equals("category")?"categories":action.equals("supplier")?"suppliers":action.equals("store")?"stores":"users";current=one(c,"SELECT * FROM "+table+" WHERE id=?",id);if(current==null)throw new IllegalArgumentException("항목을 찾을 수 없습니다.");if((action.equals("user")&&Access.admin(current))||(action.equals("admin")&&!Access.admin(current)))throw new Access.Denied();}
        switch(action){
            case "product":title=id>0?"상품 수정":"상품 등록";field(fields,current,"code","상품코드","text",true,"",null);field(fields,current,"name","상품명","text",true,"",null);field(fields,current,"category_id","카테고리 (1단계)","select",true,"",dbOptions(rows(c,"SELECT id,CASE WHEN active=1 THEN name ELSE CONCAT(name,' (사용 중지 · 기존 분류)') END name FROM categories WHERE active=1 OR id=? ORDER BY sort_order,name,id",number(current,"category_id")),"id","name"));field(fields,current,"unit","포장 단위","text",true,"",null);field(fields,current,"price","기준 공급가 (원)","number",true,"0",null);field(fields,current,"safety_stock","안전 재고 (개)","number",true,"30",null);field(fields,current,"active","판매 상태","select",true,"1",options("1","판매 중","0","판매 중지"));field(fields,current,"description","상품 설명","textarea",false,"",null);break;
            case "category":title=id>0?"카테고리 수정":"카테고리 등록";field(fields,current,"name","카테고리명","text",true,"",null);field(fields,current,"sort_order","표시 순서 (작은 숫자 먼저)","number",true,"0",null);field(fields,current,"active","사용 상태","select",true,"1",options("1","사용 중","0","중지"));break;
            case "supplier":title=id>0?"공급업체 수정":"공급업체 등록";field(fields,current,"name","공급업체명","text",true,"",null);field(fields,current,"contact","담당자","text",false,"",null);field(fields,current,"phone","연락처","tel",false,"",null);field(fields,current,"address","주소","text",false,"",null);field(fields,current,"active","사용 상태","select",true,"1",options("1","사용 중","0","중지"));break;
            case "store":title=id>0?"매장 수정":"매장 등록";field(fields,current,"code","매장코드","text",true,"",null);field(fields,current,"name","매장명","text",true,"",null);field(fields,current,"contact","담당자","text",false,"",null);field(fields,current,"phone","연락처","tel",false,"",null);field(fields,current,"address","주소","text",false,"",null);field(fields,current,"active","운영 상태","select",true,"1",options("1","사용 중","0","중지 (연결된 계정 접근 차단)"));break;
            case "user":case "admin":title=action.equals("admin")?"관리자 계정 설정":"매장 계정 설정";field(fields,current,"username","아이디","text",true,"",null);field(fields,current,"name","이름","text",true,"",null);field(fields,current,"phone","연락처","tel",false,"",null);field(fields,new HashMap<>(),"password",id>0?"새 비밀번호 (변경할 때만 입력)":"비밀번호 (12~128자)","password",id==0,"",null);if(action.equals("user"))field(fields,current,"store_id","소속 매장","select",true,"",dbOptions(stores(),"id","name"));field(fields,current,"role","권한","select",true,action.equals("user")?"STORE_ORDER":"ADMIN",action.equals("user")?options("STORE_ORDER","조회 및 발주","STORE_VIEW","조회 전용"):options("ADMIN","운영 관리자","SUPER","최고 관리자"));field(fields,current,"active","계정 상태","select",true,"1",options("1","사용 중","0","중지"));break;
            case "price":title="매장별 공급가 설정";field(fields,current,"store_id","적용 매장","select",true,"",dbOptions(stores(),"id","name"));List<Map<String,Object>> opts=dbOptions(products(null,false),"id","name");opts.add(0,option("","전체 판매 상품"));field(fields,current,"product_id","적용 상품","select",false,id==0?"":id,opts);field(fields,current,"mode","적용 방식","select",true,"discount",options("discount","기준가 대비 할인 (%)","markup","기준가 대비 할증 (%)","direct","직접 단가 (원)"));field(fields,current,"value","비율 또는 금액","number",true,"0",null);break;
            case "receipt":case "adjust":title=action.equals("receipt")?"입고 등록":"재고 수동 조정";List<Map<String,Object>> ps=products(null,false);field(fields,current,"product_id","상품","select",true,id==0?"":id,dbOptions(ps,"id","name"));field(fields,current,"quantity",action.equals("receipt")?"입고 수량":"조정 후 재고","number",true,"0",null);if(action.equals("adjust")){Map<String,Object> selected=one(c,"SELECT stock FROM products WHERE id=?",id);field(fields,current,"expected_stock","조회 당시 재고 (동시 변경 확인)","number",true,selected==null?"0":selected.get("stock"),null);}else field(fields,current,"supplier_id","공급업체","select",true,"",dbOptions(rows(c,"SELECT id,name FROM suppliers WHERE active=1 ORDER BY name,id"),"id","name"));field(fields,current,"occurred_on","처리일","date",true,LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),null);field(fields,current,"note","처리 사유 / 메모","textarea",true,"",null);break;
            case "ship":case "return":title=action.equals("ship")?"출고 등록":"반품 등록";detail(id);field(fields,current,"order_id","발주 ID","hidden",true,id,null);if(action.equals("ship")){field(fields,current,"occurred_on","출고일","date",true,LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),null);}
                else {@SuppressWarnings("unchecked") List<Map<String,Object>> lines=(List<Map<String,Object>>)req.getAttribute("lines");field(fields,current,"line_id","반품 상품","select",true,"",dbOptions(lines,"id","product_name"));field(fields,current,"quantity","반품 수량 (음수)","negative",true,"-1",null);field(fields,current,"restock","반품 재고 처리","select",true,"0",options("0","재입고하지 않음 (파손 등)","1","반품 수령 완료 · 재고에 추가"));}field(fields,current,"note","사유 / 메모","textarea",action.equals("return"),"",null);break;
            case "password":title="비밀번호 변경";field(fields,current,"current_password","현재 비밀번호","password",true,"",null);field(fields,current,"password","새 비밀번호 (12~128자)","password",true,"",null);field(fields,current,"confirm_password","새 비밀번호 확인","password",true,"",null);break;
            case "import":title="상품 엑셀 업로드";field(fields,current,"workbook","엑셀 파일 (.xlsx)","file",true,"",null);field(fields,current,"mode","중복 상품코드 처리","select",true,"skip",options("skip","기존 상품 건너뛰기","update","기존 상품 정보 갱신"));break;
            default:throw new IllegalArgumentException("지원하지 않는 등록 화면입니다.");
        }
        put("formTitle",title);put("fields",fields);put("formAction",action);put("formId",Arrays.asList("product","category","supplier","store","user","admin").contains(action)?id:0);put("formVersion",current.getOrDefault("version",0));
    }
    private static Map<String,Object> option(Object value,Object label){Map<String,Object> o=new HashMap<>();o.put("value",value);o.put("label",label);return o;}
    private static List<Map<String,Object>> options(String... pairs){List<Map<String,Object>> options=new ArrayList<>();for(int i=0;i<pairs.length;i+=2)options.add(option(pairs[i],pairs[i+1]));return options;}
    private static List<Map<String,Object>> dbOptions(List<Map<String,Object>> rows,String key,String label){List<Map<String,Object>> options=new ArrayList<>();for(Map<String,Object> row:rows)options.add(option(row.get(key),row.get(label)));return options;}
    private static void field(List<Map<String,Object>> fields,Map<String,Object> current,String name,String label,String type,boolean required,Object fallback,List<Map<String,Object>> options){Map<String,Object> f=new HashMap<>();f.put("name",name);f.put("label",label);f.put("type",type);f.put("required",required);Object value=current.getOrDefault(name,fallback);if(value instanceof Boolean)value=(Boolean)value?"1":"0";f.put("value",value);f.put("options",options);fields.add(f);}
}
