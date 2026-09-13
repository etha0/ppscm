package kr.bujobank.scm;
import org.junit.*;
import static org.junit.Assert.*;
import static kr.bujobank.scm.Database.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;
import javax.servlet.http.HttpServletRequest;

/** Destructive fixture reset is allowed ONLY on the dedicated loopback scm_test database. */
public class ScmIntegrationTest {
    private static Database db;private static ScmService service;private static String hash;
    private long admin,operator,store1,store2,buyer,other,viewer,p1,p2,cat1,cat2;
    @BeforeClass public static void setupDatabase()throws Exception{
        Assume.assumeTrue(Boolean.getBoolean("scm.integration"));db=new Database();
        assertEquals("jdbc:mariadb://127.0.0.1:3387/scm_test",db.setting("SCM_DB_URL",""));db.initialize();try(Connection c=db.open()){CategoryMigration.migrate(c);SupplierMigration.migrate(c);}service=new ScmService(db);hash=Passwords.hash("Valid-Password-2026!");
    }
    @Before public void fixtures()throws Exception{
        try(Connection c=db.open()){
            for(String t:Arrays.asList("audit_events","action_requests","order_returns","inventory_events","suppliers","shipment_lines","shipments","order_lines","purchase_orders","store_prices","product_images","products","categories","users","stores"))execute(c,"DELETE FROM "+t);
            store1=insert(c,"INSERT INTO stores(code,name) VALUES('S1','첫 번째 매장')");store2=insert(c,"INSERT INTO stores(code,name) VALUES('S2','두 번째 매장')");
            admin=account(c,"admin","SUPER",null);operator=account(c,"operator","ADMIN",null);buyer=account(c,"buyer","STORE_ORDER",store1);other=account(c,"other","STORE_ORDER",store2);viewer=account(c,"viewer","STORE_VIEW",store1);
            cat1=insert(c,"INSERT INTO categories(name) VALUES('위생용품')");cat2=insert(c,"INSERT INTO categories(name) VALUES('청소용품')");
            p1=insert(c,"INSERT INTO products(code,name,category,category_id,unit,price,stock,description) VALUES('P1','상품 하나','위생용품',?,'박스',100.50,20,'')",cat1);p2=insert(c,"INSERT INTO products(code,name,category,category_id,unit,price,stock,description) VALUES('P2','상품 둘','청소용품',?,'박스',200,3,'')",cat2);execute(c,"INSERT INTO store_prices(store_id,product_id,price) VALUES(?,?,80.25)",store1,p1);
        }
    }
    private long account(Connection c,String name,String role,Long store)throws SQLException{return insert(c,"INSERT INTO users(username,password_hash,name,role,store_id) VALUES(?,?,?,?,?)",name,hash,name,role,store);}
    private static Map<String,String[]> params(Object... pairs){Map<String,String[]> p=new HashMap<>();for(int i=0;i<pairs.length;i+=2)p.put(pairs[i].toString(),new String[]{pairs[i+1].toString()});return p;}
    private String save(long actor,String action,Map<String,String[]> p)throws Exception{return service.save(actor,0,Passwords.token(),action,p,Collections.emptyList());}
    private long order(long actor,long product,int qty)throws Exception{String route=save(actor,"order",params("product_id",product,"quantity",qty,"store_id",store2,"price","0.01"));return Long.parseLong(route.substring(route.indexOf('=')+1));}
    private Map<String,Object> row(String sql,Object... args)throws SQLException{try(Connection c=db.open()){return one(c,sql,args);}}
    private long line(long order)throws SQLException{return number(row("SELECT id FROM order_lines WHERE order_id=? ORDER BY id",order),"id");}
    @Test public void loginAndLockout()throws Exception{assertNotNull(service.login("buyer","Valid-Password-2026!"));assertNull(service.login("does-not-exist","x"));for(int i=0;i<5;i++)assertNull(service.login("buyer","wrong-password"));assertNull(service.login("buyer","Valid-Password-2026!"));assertNotNull(row("SELECT locked_until FROM users WHERE id=?",buyer).get("locked_until"));}
    @Test public void serverUsesOwnStoreAndPriceSnapshot()throws Exception{long id=order(buyer,p1,2);Map<String,Object> o=row("SELECT * FROM purchase_orders WHERE id=?",id);assertEquals(store1,number(o,"store_id"));assertEquals("80.25",row("SELECT unit_price FROM order_lines WHERE order_id=?",id).get("unit_price").toString());save(admin,"price",params("store_id",store1,"product_id",p1,"mode","direct","value","12.00"));assertEquals("80.25",row("SELECT unit_price FROM order_lines WHERE order_id=?",id).get("unit_price").toString());long second=order(buyer,p1,1);assertEquals("12.00",row("SELECT unit_price FROM order_lines WHERE order_id=?",second).get("unit_price").toString());}
    @Test public void readonlyAndCrossStoreDenied()throws Exception{assertThrows(Access.Denied.class,()->order(viewer,p1,1));long id=order(buyer,p1,2);assertThrows(Access.Denied.class,()->save(other,"cancel",params("order_id",id,"note","잘못된 요청")));assertThrows(Access.Denied.class,()->save(viewer,"cancel",params("order_id",id,"note","조회 전용")));try(Connection c=db.open()){assertThrows(Access.Denied.class,()->Access.requireStore(Access.user(c,other,false),one(c,"SELECT * FROM purchase_orders WHERE id=?",id)));}}
    @Test public void duplicateRequestDoesNotCreateSecondOrder()throws Exception{String token=Passwords.token();Map<String,String[]> p=params("product_id",p1,"quantity",1);service.save(buyer,0,token,"order",p,Collections.emptyList());assertThrows(IllegalArgumentException.class,()->service.save(buyer,0,token,"order",p,Collections.emptyList()));assertEquals(1,number(row("SELECT COUNT(*) n FROM purchase_orders"),"n"));}
    @Test public void partialShipmentAndReturnTransactions()throws Exception{long id=order(buyer,p1,5),line=line(id);save(admin,"ship",params("order_id",id,"qty_"+line,2,"occurred_on","2026-09-06","note","1차"));assertEquals("부분 출고",row("SELECT status FROM purchase_orders WHERE id=?",id).get("status"));assertEquals(18,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));assertThrows(IllegalArgumentException.class,()->save(buyer,"cancel",params("order_id",id,"note","불가")));save(admin,"return",params("order_id",id,"line_id",line,"quantity",-1,"restock",1,"note","수령 완료"));assertEquals(19,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));save(admin,"return",params("order_id",id,"line_id",line,"quantity",-1,"restock",0,"note","파손"));assertEquals(19,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));assertThrows(IllegalArgumentException.class,()->save(admin,"return",params("order_id",id,"line_id",line,"quantity",-1,"restock",1,"note","초과")));save(admin,"ship",params("order_id",id,"qty_"+line,3,"occurred_on","2026-09-06"));assertEquals("출고 완료",row("SELECT status FROM purchase_orders WHERE id=?",id).get("status"));assertEquals(2,number(row("SELECT COUNT(*) n FROM shipments WHERE order_id=?",id),"n"));}
    @Test public void insufficientStockRollsBackEntireShipment()throws Exception{Map<String,String[]> cart=new HashMap<>();cart.put("product_id",new String[]{""+p1,""+p2});cart.put("quantity",new String[]{"2","5"});String route=save(buyer,"order",cart);long id=Long.parseLong(route.split("=")[1]);List<Map<String,Object>> lines;try(Connection c=db.open()){lines=rows(c,"SELECT id,product_id FROM order_lines WHERE order_id=?",id);}Map<String,String[]> ship=params("order_id",id,"occurred_on","2026-09-06");for(Map<String,Object> line:lines)ship.put("qty_"+line.get("id"),new String[]{number(line,"product_id")==p1?"2":"5"});assertThrows(IllegalArgumentException.class,()->save(admin,"ship",ship));assertEquals(20,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));assertEquals(0,number(row("SELECT COUNT(*) n FROM shipments"),"n"));assertEquals(0,number(row("SELECT SUM(shipped) n FROM order_lines"),"n"));}
    @Test public void cancelBeforeShipmentAndRejectLaterShipping()throws Exception{long id=order(buyer,p1,1),line=line(id);save(buyer,"cancel",params("order_id",id,"note","오발주"));assertThrows(IllegalArgumentException.class,()->save(admin,"ship",params("order_id",id,"qty_"+line,1,"occurred_on","2026-09-06")));assertEquals(20,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));}
    @Test public void concurrentShippingNeverOverships()throws Exception{long id=order(buyer,p1,4),line=line(id);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> jobs=new ArrayList<>();try{for(long actor:new long[]{admin,operator})jobs.add(pool.submit(()->{start.await();try{save(actor,"ship",params("order_id",id,"qty_"+line,3,"occurred_on","2026-09-06"));return true;}catch(IllegalArgumentException e){return false;}}));start.countDown();int ok=0;for(Future<Boolean> job:jobs)if(job.get(15,TimeUnit.SECONDS))ok++;assertEquals(1,ok);assertEquals(3,number(row("SELECT shipped FROM order_lines WHERE id=?",line),"shipped"));assertEquals(17,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));}finally{pool.shutdownNow();}}
    @Test public void staleInventoryAndRevokedAccountFail()throws Exception{assertThrows(IllegalArgumentException.class,()->save(admin,"adjust",params("product_id",p1,"quantity",10,"expected_stock",19,"occurred_on","2026-09-06","note","실사")));save(admin,"adjust",params("product_id",p1,"quantity",10,"expected_stock",20,"occurred_on","2026-09-06","note","실사"));assertEquals(10,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));try(Connection c=db.open()){execute(c,"UPDATE users SET auth_version=auth_version+1 WHERE id=?",buyer);}assertThrows(Access.Denied.class,()->order(buyer,p1,1));try(Connection c=db.open()){execute(c,"UPDATE stores SET active=0 WHERE id=?",store2);}assertThrows(Access.Denied.class,()->order(other,p1,1));}
    @Test public void adminRoleEscalationIsBlocked()throws Exception{assertThrows(Access.Denied.class,()->save(operator,"admin",params()));assertThrows(Access.Denied.class,()->save(operator,"user",params("id",admin,"version",0)));assertThrows(Access.Denied.class,()->save(buyer,"receipt",params()));}
    @Test public void readModelsAreScopedByActorNotParameters()throws Exception{order(buyer,p1,1);order(other,p2,2);Map<String,Object> attrs=new HashMap<>();HttpServletRequest req=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(proxy,method,args)->{if(method.getName().equals("setAttribute")){attrs.put((String)args[0],args[1]);return null;}if(method.getName().equals("getAttribute"))return attrs.get(args[0]);if(method.getName().equals("getParameter"))return args[0].equals("storeId")?Long.toString(store2):null;return null;});try(Connection c=db.open()){new PageModel(c,req,Access.user(c,buyer,false)).load("history");@SuppressWarnings("unchecked") List<Map<String,Object>> orders=(List<Map<String,Object>>)attrs.get("orders");assertEquals(1,orders.size());assertEquals(store1,number(orders.get(0),"store_id"));new PageModel(c,req,Access.user(c,buyer,false)).load("catalog");@SuppressWarnings("unchecked") List<Map<String,Object>> products=(List<Map<String,Object>>)attrs.get("products");for(Map<String,Object> p:products)if(number(p,"id")==p1)assertEquals("80.25",p.get("effective_price").toString());}}
    @Test public void adminReadModelsAndEmptySelections()throws Exception{Map<String,Object> attrs=new HashMap<>();HttpServletRequest req=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(proxy,method,args)->{if(method.getName().equals("setAttribute")){attrs.put((String)args[0],args[1]);return null;}if(method.getName().equals("getAttribute"))return attrs.get(args[0]);if(method.getName().equals("getParameter"))return null;return null;});try(Connection c=db.open()){for(String page:Arrays.asList("categories","dashboard","reports","products","prices","inventory","stores","users","admins","orders","shipments","receipts","product-form","password")){new PageModel(c,req,Access.user(c,admin,false)).load(page);}assertNotNull(attrs.get("stats"));}}
    @Test public void suppliersAndReceiptHistory()throws Exception{
        long users=number(row("SELECT COUNT(*) n FROM users"),"n");
        save(operator,"supplier",params("name","공급 A","contact","담당자","phone","02-123-4567","address","서울","active",1));
        long supplier=number(row("SELECT id FROM suppliers WHERE name='공급 A'"),"id");
        Map<String,String[]> receipt=params("product_id",p1,"quantity",4,"supplier_id",supplier,"supplier","조작된 이름","occurred_on","2026-09-06","note","입고 검증");
        save(operator,"receipt",receipt);
        assertEquals(24,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));
        assertEquals(supplier,number(row("SELECT supplier_id FROM inventory_events WHERE product_id=?",p1),"supplier_id"));
        assertEquals("공급 A",row("SELECT supplier FROM inventory_events WHERE product_id=?",p1).get("supplier"));
        save(admin,"supplier",params("id",supplier,"version",0,"name","공급 B","active",0));
        assertEquals("공급 A",row("SELECT supplier FROM inventory_events WHERE product_id=?",p1).get("supplier"));
        assertThrows(IllegalArgumentException.class,()->save(admin,"receipt",receipt));
        assertThrows(IllegalArgumentException.class,()->save(admin,"supplier",params("name"," 공급 B ","active",1)));
        assertThrows(IllegalArgumentException.class,()->save(admin,"supplier",params("id",supplier,"version",0,"name","이전 수정","active",1)));
        assertThrows(Access.Denied.class,()->save(buyer,"supplier",params("name","불가","active",1)));
        receipt.remove("supplier_id");assertThrows(IllegalArgumentException.class,()->save(admin,"receipt",receipt));
        receipt.put("supplier_id",new String[]{"99999999"});assertThrows(IllegalArgumentException.class,()->save(admin,"receipt",receipt));
        assertEquals(24,number(row("SELECT stock FROM products WHERE id=?",p1),"stock"));
        assertEquals(1,number(row("SELECT COUNT(*) n FROM inventory_events"),"n"));
        assertEquals(users,number(row("SELECT COUNT(*) n FROM users"),"n"));
    }
    @Test public void supplierMigrationPreservesLabelsAndCanRepeat()throws Exception{
        String temporary="scm_supplier_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=db.open()){String original=c.getCatalog();execute(c,"CREATE DATABASE "+temporary+" CHARACTER SET utf8mb4");try{
            c.setCatalog(temporary);execute(c,"CREATE TABLE inventory_events(id BIGINT PRIMARY KEY AUTO_INCREMENT,kind VARCHAR(20),supplier VARCHAR(100) NOT NULL) ENGINE=InnoDB");
            execute(c,"INSERT INTO inventory_events(kind,supplier) VALUES('입고',' 공급 A '),('입고','공급 A'),('입고',''),('출고','공급 C')");
            SupplierMigration.migrate(c);assertEquals(1,number(one(c,"SELECT COUNT(*) n FROM suppliers"),"n"));
            assertEquals(2,number(one(c,"SELECT COUNT(*) n FROM inventory_events WHERE supplier_id IS NOT NULL"),"n"));
            assertEquals(" 공급 A ",one(c,"SELECT supplier FROM inventory_events WHERE id=1").get("supplier"));
            long id=number(one(c,"SELECT supplier_id FROM inventory_events WHERE id=1"),"supplier_id");
            execute(c,"UPDATE suppliers SET name='공급 B',active=0 WHERE id=?",id);SupplierMigration.migrate(c);
            assertEquals(id,number(one(c,"SELECT supplier_id FROM inventory_events WHERE id=1"),"supplier_id"));
            assertEquals(1,number(one(c,"SELECT COUNT(*) n FROM suppliers"),"n"));
            assertThrows(SQLException.class,()->execute(c,"UPDATE inventory_events SET supplier_id=99999 WHERE id=1"));
        }finally{c.setCatalog(original);execute(c,"DROP DATABASE "+temporary);}}
    }
    private Map<String,String[]> productInput(long category){return params("code","NEW-P","name","신규 상품","category_id",category,"unit","박스","price","12.34","safety_stock",3,"active",1,"description","");}
    @Test public void categoryCrudAssignmentAndRename()throws Exception{
        save(operator,"category",params("name","새 분류","sort_order",5,"active",1));
        long category=number(row("SELECT id FROM categories WHERE name='새 분류'"),"id");
        save(admin,"product",productInput(category));
        assertEquals(category,number(row("SELECT category_id FROM products WHERE code='NEW-P'"),"category_id"));
        save(admin,"category",params("id",category,"version",0,"name","변경된 분류","sort_order",2,"active",1));
        try(Connection c=db.open()){List<Map<String,Object>> products=new PageModel(c,null,null).products(null,false);assertTrue(products.stream().anyMatch(p->"NEW-P".equals(p.get("code"))&&"변경된 분류".equals(p.get("category"))));}
        assertThrows(IllegalArgumentException.class,()->save(admin,"category",params("name"," 변경된 분류 ","sort_order",0,"active",1)));
        assertThrows(IllegalArgumentException.class,()->save(admin,"category",params("id",category,"version",0,"name","오래된 수정","sort_order",0,"active",1)));
        assertThrows(Access.Denied.class,()->save(buyer,"category",params("name","권한 없음","sort_order",0,"active",1)));
        assertThrows(IllegalArgumentException.class,()->save(admin,"product",productInput(99999999)));
    }
    @Test public void inactiveCategoryPreventsNewAssignmentButKeepsExistingProduct()throws Exception{
        save(admin,"category",params("id",cat1,"version",0,"name","위생용품","sort_order",0,"active",0));
        assertThrows(IllegalArgumentException.class,()->save(admin,"product",productInput(cat1)));
        Map<String,String[]> edit=productInput(cat1);edit.putAll(params("id",p1,"version",0,"code","P1","name","수정 상품"));save(admin,"product",edit);
        assertEquals(cat1,number(row("SELECT category_id FROM products WHERE id=?",p1),"category_id"));
        Map<String,String[]> move=productInput(cat1);move.putAll(params("id",p2,"version",0,"code","P2"));assertThrows(IllegalArgumentException.class,()->save(admin,"product",move));
        assertTrue(order(buyer,p1,1)>0);
        try(Connection c=db.open()){assertEquals(2,new PageModel(c,null,null).products(store1,true).size());}
        save(admin,"category",params("id",cat1,"version",1,"name","위생용품","sort_order",0,"active",1));save(admin,"product",productInput(cat1));
    }
    @Test public void categoryMigrationPreservesLegacyProductsAndCanRepeat()throws Exception{
        String temporary="scm_category_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=db.open()){String original=c.getCatalog();execute(c,"CREATE DATABASE "+temporary+" CHARACTER SET utf8mb4");try{
            c.setCatalog(temporary);execute(c,"CREATE TABLE products(id BIGINT PRIMARY KEY AUTO_INCREMENT,code VARCHAR(30),category VARCHAR(60) NOT NULL,stock INT NOT NULL) ENGINE=InnoDB");
            execute(c,"INSERT INTO products(code,category,stock) VALUES('A',' 분류 ',17),('B','분류',23),('C','',9)");
            CategoryMigration.migrate(c);assertEquals(2,number(one(c,"SELECT COUNT(*) n FROM categories"),"n"));assertEquals(3,number(one(c,"SELECT COUNT(*) n FROM products p JOIN categories c ON c.id=p.category_id"),"n"));assertEquals(49,number(one(c,"SELECT SUM(stock) n FROM products"),"n"));
            long id=number(one(c,"SELECT category_id FROM products WHERE code='A'"),"category_id");CategoryMigration.migrate(c);assertEquals(id,number(one(c,"SELECT category_id FROM products WHERE code='A'"),"category_id"));assertEquals(2,number(one(c,"SELECT COUNT(*) n FROM categories"),"n"));assertThrows(SQLException.class,()->execute(c,"UPDATE products SET category_id=99999 WHERE code='A'"));
        }finally{c.setCatalog(original);execute(c,"DROP DATABASE "+temporary);}}
    }
}
