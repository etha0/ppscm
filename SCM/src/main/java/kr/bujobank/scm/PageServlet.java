package kr.bujobank.scm;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import javax.servlet.*;
import javax.servlet.annotation.*;
import javax.servlet.http.*;
import static kr.bujobank.scm.Database.*;
@WebServlet("/app/*")
@MultipartConfig(maxFileSize=5242880,maxRequestSize=33554432,fileSizeThreshold=1048576)
public class PageServlet extends HttpServlet {
    private Database db; private ScmService service; private ProductCodeImages codeImages;
    public void init() throws ServletException {try{db=new Database();db.initialize();codeImages=new ProductCodeImages(db.setting("SCM_UPLOAD_IMAGES_DIR",""));ProductImageMigration.migrate(db,codeImages);service=new ScmService(db);}catch(Exception e){throw new ServletException("SCM 데이터베이스 초기화 실패. 서버 설정과 로그를 확인하세요.",e);}}
    private static final Map<String,String[]> PAGES = new LinkedHashMap<>();
    static {
        PAGES.put("dashboard", new String[]{"대시보드", "오늘의 운영 현황을 한눈에 확인하세요.", "운영 현황"});
        PAGES.put("categories",new String[]{"카테고리 관리","상품 분류와 표시 순서를 관리하세요.","상품 운영"});
        PAGES.put("products", new String[]{"상품 관리", "상품 정보와 판매 상태를 관리하세요.", "상품 운영"});
        PAGES.put("prices", new String[]{"매장별 가격", "매장에 맞는 공급 가격을 설정하세요.", "상품 운영"});
        PAGES.put("suppliers",new String[]{"공급업체 관리","공급업체 정보와 사용 상태를 관리하세요.","입출고 · 재고"});
        PAGES.put("receipts", new String[]{"입고 관리", "입고 내역을 확인하고 신규 입고를 등록하세요.", "입출고 · 재고"});
        PAGES.put("orders", new String[]{"수주 관리", "매장에서 접수한 발주와 처리 상태를 확인하세요.", "입출고 · 재고"});
        PAGES.put("shipments", new String[]{"출고 관리", "미출고 상품을 확인하고 나누어 출고하세요.", "입출고 · 재고"});
        PAGES.put("inventory", new String[]{"재고 관리", "현재 재고와 조정 이력을 확인하세요.", "입출고 · 재고"});
        PAGES.put("stores", new String[]{"매장 관리", "거래 매장의 기본 정보와 운영 상태를 관리하세요.", "관리 설정"});
        PAGES.put("users", new String[]{"매장 사용자", "매장별 계정과 발주 권한을 관리하세요.", "관리 설정"});
        PAGES.put("admins", new String[]{"관리자 관리", "SCM에 접속할 관리자 계정을 관리하세요.", "관리 설정"});
        PAGES.put("reports", new String[]{"통계 · 리포트", "매장별, 날짜별, 상품별 실적을 확인하세요.", "운영 현황"});
        PAGES.put("catalog", new String[]{"상품 발주", "필요한 상품을 선택하고 발주서를 작성하세요.", "매장 발주"});
        PAGES.put("history", new String[]{"발주 내역", "우리 매장의 발주와 출고 진행 상황을 확인하세요.", "매장 발주"});
        PAGES.put("detail", new String[]{"발주 상세", "발주 상품과 분할 출고 이력을 확인하세요.", "매장 발주"});
        PAGES.put("product-form", new String[]{"상품 등록", "상품의 기본 정보와 이미지를 등록하세요.", "상품 운영"});
        PAGES.put("edit",new String[]{"정보 등록 · 수정","변경할 내용을 확인하고 저장하세요.","관리 설정"});
        PAGES.put("password",new String[]{"비밀번호 변경","계정 비밀번호를 안전하게 변경하세요.","내 계정"});
    }
    private String path(HttpServletRequest req){String path=req.getPathInfo();return path==null||path.equals("/")?"login":path.substring(1);}
    private void headers(HttpServletRequest req,HttpServletResponse resp)throws UnsupportedEncodingException{
        req.setCharacterEncoding("UTF-8");resp.setCharacterEncoding("UTF-8");resp.setHeader("Cache-Control","no-store");resp.setHeader("X-Content-Type-Options","nosniff");resp.setHeader("X-Frame-Options","DENY");resp.setHeader("Referrer-Policy","same-origin");
        HttpSession session=req.getSession();if(session.getAttribute("csrf")==null)session.setAttribute("csrf",Passwords.token());
    }
    private Map<String,Object> actor(HttpServletRequest req)throws SQLException{
        HttpSession session=req.getSession(false);if(session==null||session.getAttribute("userId")==null)return null;
        try(Connection c=db.open()){Map<String,Object> u=Access.user(c,((Number)session.getAttribute("userId")).longValue(),false);if(number(u,"auth_version")!=((Number)session.getAttribute("authVersion")).longValue())throw new Access.Denied();return u;}
        catch(Access.Denied e){session.invalidate();req.getSession(true).setAttribute("csrf",Passwords.token());return null;}
    }
    protected void doGet(HttpServletRequest req,HttpServletResponse resp)throws ServletException,IOException{
        headers(req,resp);String page=path(req);
        try{
            Map<String,Object> actor=actor(req);
            if(page.equals("login")){if(actor!=null){redirect(req,resp,Access.admin(actor)?"dashboard":"catalog");return;}req.getSession();req.getRequestDispatcher("/WEB-INF/views/login.jsp").forward(req,resp);return;}
            if(actor==null){redirect(req,resp,"login");return;}
            if(page.equals("image")){image(req,resp,actor);return;}
            if(page.equals("inventory-history")){
                Access.requireAdmin(actor);
                try(Connection c=db.open()){new PageModel(c,req,actor).inventoryHistory();}
                req.getRequestDispatcher("/WEB-INF/views/inventory-history.jsp").forward(req,resp);return;
            }
            if(page.equals("export-products")){Access.requireAdmin(actor);ExcelProducts.exportFile(db,resp);return;}
            if(!PAGES.containsKey(page)){resp.sendError(404);return;}
            Access.page(actor,page);
            req.setAttribute("page",page);req.setAttribute("title",PAGES.get(page)[0]);req.setAttribute("subtitle",PAGES.get(page)[1]);req.setAttribute("pages",PAGES);
            try(Connection c=db.open()){new PageModel(c,req,actor).load(page);decorateCodeImages(req);}
            Object flash=req.getSession().getAttribute("flash");if(flash!=null){req.setAttribute("flash",flash);req.getSession().removeAttribute("flash");}
            req.getRequestDispatcher("/WEB-INF/views/layout.jsp").forward(req,resp);
        }catch(Access.Denied e){error(req,resp,403,e.getMessage());}catch(IllegalArgumentException e){error(req,resp,400,e.getMessage());}catch(SQLException e){getServletContext().log("SCM 조회 실패",e);error(req,resp,503,"데이터베이스 연결 또는 조회에 실패했습니다. 관리자에게 문의하세요.");}catch(RuntimeException e){getServletContext().log("SCM 화면 처리 실패",e);error(req,resp,500,"화면을 불러오지 못했습니다. 관리자에게 문의하세요.");}
    }
    protected void doPost(HttpServletRequest req,HttpServletResponse resp)throws ServletException,IOException{
        headers(req,resp);String route=path(req);
        try{
            String expected=(String)req.getSession().getAttribute("csrf"),actual=req.getParameter("csrf");
            if(expected==null||actual==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8))){error(req,resp,403,"요청 정보가 만료되었습니다. 이전 화면으로 돌아가 새로고침해 주세요.");return;}
            if(route.equals("login")){
                String username=req.getParameter("username");Map<String,Object> u=service.login(username==null?"":username.trim(),req.getParameter("password"));
                if(u==null){req.setAttribute("loginError","아이디 또는 비밀번호를 확인하세요. 반복 실패한 계정은 15분 후 다시 시도해 주세요.");resp.setStatus(401);req.getRequestDispatcher("/WEB-INF/views/login.jsp").forward(req,resp);return;}
                req.getSession().invalidate();HttpSession session=req.getSession(true);session.setAttribute("userId",u.get("id"));session.setAttribute("authVersion",u.get("auth_version"));session.setAttribute("csrf",Passwords.token());redirect(req,resp,Access.admin(u)?"dashboard":"catalog");return;
            }
            Map<String,Object> u=actor(req);if(u==null){redirect(req,resp,"login");return;}
            if(route.equals("logout")){req.getSession().invalidate();redirect(req,resp,"login");return;}
            if(!route.equals("save")){resp.sendError(404);return;}
            String action=req.getParameter("action"),next;
            if("import".equals(action)){Access.requireAdmin(u);next=ExcelProducts.importFile(db,u,req.getParameter("requestToken"),req.getPart("workbook"),req.getParameter("mode"));}
            else {List<ScmService.ImageData> images=Collections.emptyList();if("product".equals(action)){Access.requireAdmin(u);images=ImageFiles.read(req.getParts());}next=service.save(number(u,"id"),number(u,"auth_version"),req.getParameter("requestToken"),action,req.getParameterMap(),images);}
            if("login".equals(next)){req.getSession().invalidate();}else req.getSession().setAttribute("flash","정상적으로 처리되었습니다.");redirect(req,resp,next);
        }catch(Access.Denied e){error(req,resp,403,e.getMessage());}catch(IllegalArgumentException|IllegalStateException e){error(req,resp,400,e.getMessage()==null?"입력값 또는 업로드 파일 크기를 확인하세요.":e.getMessage());}
        catch(Exception e){getServletContext().log("SCM 처리 실패",e);if(e instanceof SQLException&&"23000".equals(((SQLException)e).getSQLState()))error(req,resp,409,"중복 코드/아이디 또는 이미 처리된 요청입니다. 입력값과 목록을 확인하세요.");else error(req,resp,503,"처리 중 오류가 발생해 변경사항을 저장하지 않았습니다. 다시 시도해 주세요.");}
    }
    @SuppressWarnings("unchecked")
    private void decorateCodeImages(HttpServletRequest req) {
        Object products=req.getAttribute("products");
        if(!(products instanceof List)) return;
        for(Map<String,Object> product:(List<Map<String,Object>>)products) {
            boolean available=codeImages.exists((String)product.get("code"));
            product.put("code_image",available);
            if(String.valueOf(product.get("id")).equals(req.getParameter("id"))){req.setAttribute("codeImageProductId",product.get("id"));req.setAttribute("imageSequences",codeImages.sequences((String)product.get("code")));}
        }
    }
    private void image(HttpServletRequest req,HttpServletResponse resp,Map<String,Object> actor)throws SQLException,IOException{
        long productId;int sequence;
        try{productId=Long.parseLong(req.getParameter("productId"));sequence=req.getParameter("seq")==null?0:Integer.parseInt(req.getParameter("seq"));if(sequence<0||sequence>6)throw new IllegalArgumentException();}catch(Exception e){resp.sendError(404);return;}
        try(Connection c=db.open()){
            Map<String,Object> product=one(c,"SELECT code,active FROM products WHERE id=?",productId);
            if(product==null||(!Access.admin(actor)&&!bool(product,"active"))){resp.sendError(404);return;}
            byte[] bytes;
            try{bytes=sequence==0?codeImages.read((String)product.get("code")):codeImages.read((String)product.get("code"),sequence);}catch(IOException e){resp.sendError(404);return;}
            resp.setContentType("image/jpeg");resp.setHeader("X-Content-Type-Options","nosniff");resp.setHeader("Cache-Control","private, no-cache");resp.setContentLength(bytes.length);resp.getOutputStream().write(bytes);
        }
    }
    private void redirect(HttpServletRequest req,HttpServletResponse resp,String route){resp.setStatus(303);resp.setHeader("Location",req.getContextPath()+"/app/"+route);}
    private void error(HttpServletRequest req,HttpServletResponse resp,int code,String message)throws ServletException,IOException{resp.setStatus(code);req.setAttribute("errorCode",code);req.setAttribute("errorMessage",message);req.getRequestDispatcher("/WEB-INF/views/error.jsp").forward(req,resp);}
}
