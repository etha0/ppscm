package kr.bujobank.scm;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.http.Part;
public final class ImageFiles {
    public static byte[] jpeg(byte[] data)throws IOException {
        java.awt.image.BufferedImage source=ImageIO.read(new ByteArrayInputStream(data));
        if(source==null)throw new IOException("이미지를 읽을 수 없습니다.");
        if(source.getWidth()>8000||source.getHeight()>8000||(long)source.getWidth()*source.getHeight()>24000000)throw new IOException("이미지 크기 제한을 초과했습니다.");
        java.awt.image.BufferedImage rgb=new java.awt.image.BufferedImage(source.getWidth(),source.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics=rgb.createGraphics();
        try{graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,rgb.getWidth(),rgb.getHeight());graphics.drawImage(source,0,0,null);}finally{graphics.dispose();}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        if(!ImageIO.write(rgb,"JPEG",output))throw new IOException("JPG 변환에 실패했습니다.");
        if(output.size()>5242880)throw new IOException("JPG 변환 결과가 5MB를 초과합니다.");
        return output.toByteArray();
    }
    public static List<ScmService.ImageData> read(Collection<Part> parts)throws IOException {
        List<ScmService.ImageData> result=new ArrayList<>();
        for(Part part:parts){if(!part.getName().equals("images")||part.getSize()==0)continue;if(result.size()>=6||part.getSize()>5242880)throw new IllegalArgumentException("이미지는 최대 6장, 각 5MB 이하입니다.");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=part.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}byte[] data=bytes.toByteArray();String mime;
            try(ImageInputStream image=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){Iterator<ImageReader> readers=ImageIO.getImageReaders(image);if(!readers.hasNext())throw new IllegalArgumentException("올바른 JPG 또는 PNG 이미지 파일을 선택하세요.");ImageReader reader=readers.next();try{reader.setInput(image);String format=reader.getFormatName();if(!format.equalsIgnoreCase("JPEG")&&!format.equalsIgnoreCase("PNG"))throw new IllegalArgumentException("JPG 또는 PNG 이미지만 사용할 수 있습니다.");if(reader.getWidth(0)>8000||reader.getHeight(0)>8000||(long)reader.getWidth(0)*reader.getHeight(0)>24000000)throw new IllegalArgumentException("이미지는 가로·세로 8000px 및 2400만 화소 이내여야 합니다.");mime=format.equalsIgnoreCase("JPEG")?"image/jpeg":"image/png";}finally{reader.dispose();}}
            result.add(new ScmService.ImageData(mime,data));
        }return result;
    }
}
