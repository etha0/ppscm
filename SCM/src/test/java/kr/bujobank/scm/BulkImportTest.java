package kr.bujobank.scm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
public class BulkImportTest {
    private byte[] workbook(String kind,String[][] data)throws Exception{
        try(Workbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet sheet=book.createSheet();Row header=sheet.createRow(0);String[] headers=ExcelOperations.headers(kind);for(int i=0;i<headers.length;i++)header.createCell(i).setCellValue(headers[i]);
            for(int r=0;r<data.length;r++){Row row=sheet.createRow(r+1);for(int c=0;c<data[r].length;c++)row.createCell(c).setCellValue(data[r][c]);}book.write(out);return out.toByteArray();
        }
    }
    @Test public void readsFixedStorePricesAndPreservesLeadingZeros()throws Exception{
        List<Map<String,String[]>> rows=ExcelOperations.parse("prices",new ByteArrayInputStream(workbook("prices",new String[][]{{"001","00012","1234.50"}})));
        assertEquals("001",ScmService.value(rows.get(0),"store_code"));assertEquals("00012",ScmService.value(rows.get(0),"code"));assertEquals("1234.50",ScmService.amount(rows.get(0),"value").toString());
    }
    @Test public void rejectsDuplicateAndInvalidPriceRows()throws Exception{
        try{ExcelOperations.parse("prices",new ByteArrayInputStream(workbook("prices",new String[][]{{"S1","P1","100"},{"s1","p1","200"}})));fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("3행"));}
        try{ExcelOperations.parse("prices",new ByteArrayInputStream(workbook("prices",new String[][]{{"S1","P1","-1"}})));fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("2행"));}
    }
    @Test public void validatesReceiptDatesAndQuantities()throws Exception{
        assertEquals(1,ExcelOperations.parse("receipts",new ByteArrayInputStream(workbook("receipts",new String[][]{{"P1","2","공급업체","2026-09-13","입고"}}))).size());
        try{ExcelOperations.parse("receipts",new ByteArrayInputStream(workbook("receipts",new String[][]{{"P1","0","공급업체","2026-09-13","입고"}})));fail();}catch(IllegalArgumentException expected){}
        try{ExcelOperations.parse("receipts",new ByteArrayInputStream(workbook("receipts",new String[][]{{"P1","2","공급업체","2026-02-30","입고"}})));fail();}catch(IllegalArgumentException expected){}
    }
    private byte[] jpg()throws Exception{java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"JPG",out);return out.toByteArray();}
    private byte[] zip(String name,byte[] data)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}return out.toByteArray();}
    @Test public void acceptsNumberedJpgAndZipAndRejectsDuplicates()throws Exception{
        BulkImages batch=new BulkImages();batch.add("P_01_1.jpg",new ByteArrayInputStream(jpg()));assertEquals("P_01",batch.items().get(0).code);
        batch.zip(new ByteArrayInputStream(zip("images/P_01_2.JPG",jpg())));assertEquals(2,batch.items().size());
        try{batch.add("p_01_1.JPG",new ByteArrayInputStream(jpg()));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void rejectsTraversalWrongNamesAndOversizedZipEntries()throws Exception{
        try{new BulkImages().zip(new ByteArrayInputStream(zip("images/",new byte[]{1})));fail();}catch(IllegalArgumentException expected){}
        try{new BulkImages().zip(new ByteArrayInputStream(zip("../P1_1.JPG",jpg())));fail();}catch(IllegalArgumentException expected){}
        try{new BulkImages().add("P1_7.JPG",new ByteArrayInputStream(jpg()));fail();}catch(IllegalArgumentException expected){}
        try{new BulkImages().zip(new ByteArrayInputStream(zip("P1_1.JPG",new byte[5242881])));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void rejectsInsufficientStockButAcceptsExactStock(){
        Map<String,Object> p=new HashMap<>();p.put("name","세제");p.put("stock",3);ScmService.requireOrderStock(p,3);
        try{ScmService.requireOrderStock(p,4);fail();}catch(IllegalArgumentException e){assertTrue(e.getMessage().contains("재고 부족"));assertTrue(e.getMessage().contains("세제"));}
    }
}