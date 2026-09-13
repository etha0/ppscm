package kr.bujobank.scm;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.servlet.http.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static kr.bujobank.scm.Database.*;
public final class ExcelProducts {
    private static final String[] HEADERS={"상품코드","상품명","카테고리","포장단위","공급가","안전재고","판매상태(1/0)","설명"};
    private static final String[] KEYS={"code","name","category","unit","price","safety_stock","active","description"};
    public static void exportFile(Database db,HttpServletResponse response)throws SQLException,IOException{
        try(Connection c=db.open();Workbook workbook=new XSSFWorkbook()){
            Sheet sheet=workbook.createSheet("상품");Row header=sheet.createRow(0);for(int i=0;i<HEADERS.length;i++){header.createCell(i).setCellValue(HEADERS[i]);sheet.setColumnWidth(i,i==7?10000:6000);}
            int index=1;for(Map<String,Object> product:rows(c,"SELECT p.*,c.name category FROM products p JOIN categories c ON c.id=p.category_id ORDER BY p.code")){Row row=sheet.createRow(index++);for(int i=0;i<KEYS.length;i++){Object v=product.get(KEYS[i]);if(KEYS[i].equals("active"))v=bool(product,"active")?"1":"0";row.createCell(i,CellType.STRING).setCellValue(v==null?"":v.toString());}}
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename=products.xlsx");workbook.write(response.getOutputStream());
        }
    }
    public static String importFile(Database db,Map<String,Object> actor,String token,Part part,String mode)throws Exception{
        if(part==null||part.getSize()==0||part.getSize()>5242880)throw new IllegalArgumentException("5MB 이하의 XLSX 파일을 선택하세요.");if(!Arrays.asList("skip","update").contains(mode))throw new IllegalArgumentException("중복 처리 방식을 선택하세요.");
        List<Map<String,String[]>> parsed=new ArrayList<>();Set<String> codes=new HashSet<>();
        try(InputStream in=part.getInputStream();Workbook workbook=new XSSFWorkbook(in)){
            if(workbook.getNumberOfSheets()==0)throw new IllegalArgumentException("엑셀 시트가 없습니다.");Sheet sheet=workbook.getSheetAt(0);if(sheet.getLastRowNum()>1000)throw new IllegalArgumentException("한 번에 최대 1000개 상품을 업로드하세요.");DataFormatter formatter=new DataFormatter(Locale.ROOT);
            Row header=sheet.getRow(0);if(header==null)throw new IllegalArgumentException("헤더 행이 없습니다.");for(int i=0;i<HEADERS.length;i++)if(!HEADERS[i].equals(formatter.formatCellValue(header.getCell(i)).trim()))throw new IllegalArgumentException("상품 엑셀 다운로드 양식의 헤더를 그대로 사용하세요.");
            for(int r=1;r<=sheet.getLastRowNum();r++){Row row=sheet.getRow(r);if(row==null)continue;Map<String,String[]> p=new HashMap<>();boolean any=false;for(int i=0;i<KEYS.length;i++){Cell cell=row.getCell(i);if(cell!=null&&cell.getCellType()==CellType.FORMULA)throw new IllegalArgumentException((r+1)+"행: 수식 셀을 값으로 바꿔주세요.");String v=formatter.formatCellValue(cell).trim();any|=!v.isEmpty();p.put(KEYS[i],new String[]{v});}if(!any)continue;
                try{String code=ScmService.text(p,"code",30,true);if(!code.matches("[A-Za-z0-9_-]+")||!codes.add(code.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("상품코드가 중복되었거나 형식이 올바르지 않습니다.");ScmService.text(p,"name",100,true);ScmService.text(p,"category",60,true);ScmService.text(p,"unit",100,true);ScmService.amount(p,"price");ScmService.integer(p,"safety_stock",0,100000000);ScmService.integer(p,"active",0,1);ScmService.text(p,"description",5000,false);}catch(IllegalArgumentException e){throw new IllegalArgumentException((r+1)+"행: "+e.getMessage());}parsed.add(p);
            }
        }catch(org.apache.poi.ooxml.POIXMLException e){throw new IllegalArgumentException("올바른 XLSX 파일이 아닙니다.");}
        if(parsed.isEmpty())throw new IllegalArgumentException("등록할 상품이 없습니다.");
        return db.transaction(c->{Map<String,Object> fresh=Access.user(c,number(actor,"id"),true);Access.requireAdmin(fresh);if(number(fresh,"auth_version")!=number(actor,"auth_version"))throw new Access.Denied();if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("요청 정보가 올바르지 않습니다.");execute(c,"INSERT INTO action_requests(token,user_id,action) VALUES(?,?,'import')",token,actor.get("id"));
            for(Map<String,String[]> p:parsed){Map<String,Object> old=one(c,"SELECT id,category_id FROM products WHERE code=? FOR UPDATE",ScmService.value(p,"code"));if(old!=null&&mode.equals("skip"))continue;Map<String,Object> category=one(c,"SELECT id FROM categories WHERE name=?",ScmService.value(p,"category"));if(category==null)throw new IllegalArgumentException("등록되지 않은 카테고리입니다: "+ScmService.value(p,"category")+". 카테고리 관리에서 먼저 등록하세요.");category=ScmService.selectCategory(c,number(category,"id"),old==null?0:number(old,"category_id"));Object[] args={ScmService.value(p,"name"),category.get("name"),category.get("id"),ScmService.value(p,"unit"),ScmService.amount(p,"price"),ScmService.integer(p,"safety_stock",0,100000000),ScmService.integer(p,"active",0,1),ScmService.value(p,"description"),ScmService.value(p,"code")};if(old==null)execute(c,"INSERT INTO products(name,category,category_id,unit,price,safety_stock,active,description,code) VALUES(?,?,?,?,?,?,?,?,?)",args);else execute(c,"UPDATE products SET name=?,category=?,category_id=?,unit=?,price=?,safety_stock=?,active=?,description=?,version=version+1 WHERE code=?",args);}
            execute(c,"INSERT INTO audit_events(actor_id,action) VALUES(?,'import')",actor.get("id"));return "products";
        });
    }
}