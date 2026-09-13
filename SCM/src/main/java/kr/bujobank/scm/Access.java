package kr.bujobank.scm;

import java.sql.*;
import java.util.*;
import static kr.bujobank.scm.Database.*;

public final class Access {
    public static class Denied extends RuntimeException { public Denied(){super("접근 권한이 없거나 사용할 수 없는 계정입니다.");} }
    public static Map<String,Object> user(Connection c,long id,boolean lock) throws SQLException {
        Map<String,Object> u=one(c,"SELECT u.*,s.name store_name,s.active store_active FROM users u LEFT JOIN stores s ON s.id=u.store_id WHERE u.id=?"+(lock?" FOR UPDATE":""),id);
        if(u==null||!bool(u,"active")||(u.get("store_id")!=null&&!bool(u,"store_active")))throw new Denied();return u;
    }
    public static boolean admin(Map<String,Object> u) {return Arrays.asList("SUPER","ADMIN").contains(u.get("role"));}
    public static boolean superuser(Map<String,Object> u){return "SUPER".equals(u.get("role"));}
    public static boolean canOrder(Map<String,Object> u){return "STORE_ORDER".equals(u.get("role"));}
    public static void requireAdmin(Map<String,Object> u){if(!admin(u))throw new Denied();}
    public static void requireSuper(Map<String,Object> u){if(!superuser(u))throw new Denied();}
    public static void requireOrder(Map<String,Object> u){if(!canOrder(u))throw new Denied();}
    public static void requireStore(Map<String,Object> u,Map<String,Object> order){if(order==null||(!admin(u)&&number(u,"store_id")!=number(order,"store_id")))throw new Denied();}
    public static void page(Map<String,Object> u,String page){
        if(!admin(u)&&!Arrays.asList("catalog","history","detail","password","image").contains(page))throw new Denied();
        if("admins".equals(page))requireSuper(u);
    }
}
