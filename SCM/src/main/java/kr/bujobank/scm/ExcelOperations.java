package kr.bujobank.scm;
import java.io.*;
import java.util.*;
import java.time.LocalDate;
import javax.servlet.http.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.NumberToTextConverter;
public final class ExcelOperations {
    static String[] headers(String kind){if("prices".equals(kind))return new String[]{"매장코드","상품코드","적용가격"};if("receipts".equals(kind))return new String[]{"상품코드","입고수량","공급업체","입고일","메모"};throw new IllegalArgumentException("지원하지 않는 양식입니다.");}
    public static void template(String kind,HttpServletResponse response)throws IOException{
        try(Workbook workbook=new XSSFWorkbook()){Sheet sheet=workbook.createSheet("업로드");Row row=sheet.createRow(0);String[] headers=headers(kind);for(int i=0;i<headers.length;i++){row.createCell(i).setCellValue(headers[i]);sheet.setColumnWidth(i,6500);}
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename="+kind+"-template.xlsx");workbook.write(response.getOutputStream());}
    }
    public static List<Map<String,String[]>> read(String kind,Part part)throws IOException{
        if(part==null||part.getSize()==0||part.getSize()>5242880)throw new IllegalArgumentException("5MB 이하 XLSX 파일을 선택하세요.");
        try(InputStream input=part.getInputStream()){return parse(kind,input);}
    }
    static List<Map<String,String[]>> parse(String kind,InputStream input)throws IOException{
        String[] headers=headers(kind),keys="prices".equals(kind)?new String[]{"store_code","code","value"}:new String[]{"code","quantity","supplier","occurred_on","note"};
        List<Map<String,String[]>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        try(Workbook book=new XSSFWorkbook(input)){
            if(book.getNumberOfSheets()==0)throw new IllegalArgumentException("시트가 없습니다.");Sheet sheet=book.getSheetAt(0);if(sheet.getLastRowNum()>1000)throw new IllegalArgumentException("최대 1000행까지 업로드할 수 있습니다.");
            DataFormatter formatter=new DataFormatter(Locale.ROOT);Row header=sheet.getRow(0);if(header==null)throw new IllegalArgumentException("양식 헤더가 없습니다.");
            for(int i=0;i<headers.length;i++)if(!headers[i].equals(formatter.formatCellValue(header.getCell(i)).trim()))throw new IllegalArgumentException("다운로드한 양식의 헤더를 사용하세요.");
            for(int r=1;r<=sheet.getLastRowNum();r++){
                Row row=sheet.getRow(r);if(row==null)continue;Map<String,String[]> values=new HashMap<>();boolean any=false;
                try{
                    for(int i=0;i<keys.length;i++){
                        Cell cell=row.getCell(i);String value=formatter.formatCellValue(cell).trim();
                        if(cell!=null&&cell.getCellType()==CellType.FORMULA)throw new IllegalArgumentException("수식은 값으로 변환하세요.");
                        if(cell!=null&&cell.getCellType()==CellType.NUMERIC){if(keys[i].equals("occurred_on")&&DateUtil.isCellDateFormatted(cell))value=DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate().toString();else if(Arrays.asList("quantity","value").contains(keys[i]))value=NumberToTextConverter.toText(cell.getNumericCellValue());}
                        values.put(keys[i],new String[]{value});any|=!value.isEmpty();
                    }
                    if(!any)continue;
                    String code=ScmService.text(values,"code",30,true);if(!code.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("상품코드를 확인하세요.");
                    if(kind.equals("prices")){String store=ScmService.text(values,"store_code",30,true);ScmService.amount(values,"value");if(!seen.add((store+"/"+code).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("매장/상품 조합이 중복되었습니다.");}
                    else{ScmService.integer(values,"quantity",1,100000000);ScmService.text(values,"supplier",100,true);ScmService.text(values,"note",1000,true);try{LocalDate.parse(ScmService.value(values,"occurred_on"));}catch(Exception e){throw new IllegalArgumentException("입고일은 YYYY-MM-DD 형식으로 입력하세요.");}}
                    values.put("row",new String[]{Integer.toString(r+1)});result.add(values);
                }catch(IllegalArgumentException e){throw new IllegalArgumentException((r+1)+"행: "+e.getMessage());}
            }
        }catch(org.apache.poi.ooxml.POIXMLException e){throw new IllegalArgumentException("올바른 XLSX 파일이 아닙니다.");}
        if(result.isEmpty())throw new IllegalArgumentException("업로드할 데이터가 없습니다.");return result;
    }
}