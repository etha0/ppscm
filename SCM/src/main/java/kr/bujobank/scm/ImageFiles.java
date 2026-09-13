package kr.bujobank.scm;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.http.Part;
public final class ImageFiles {
    public static List<ScmService.ImageData> read(Collection<Part> parts)throws IOException {
        List<ScmService.ImageData> result=new ArrayList<>();
        for(Part part:parts){if(!part.getName().equals("images")||part.getSize()==0)continue;if(result.size()>=6||part.getSize()>5242880)throw new IllegalArgumentException("이미지는 최대 6장, 각 5MB 이하입니다.");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=part.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}byte[] data=bytes.toByteArray();String mime;
            try(ImageInputStream image=ImageIO.createImageInputStream(new ByteArrayInputStream(data))){Iterator<ImageReader> readers=ImageIO.getImageReaders(image);if(!readers.hasNext())throw new IllegalArgumentException("올바른 JPG 또는 PNG 이미지 파일을 선택하세요.");ImageReader reader=readers.next();try{reader.setInput(image);String format=reader.getFormatName();if(!format.equalsIgnoreCase("JPEG")&&!format.equalsIgnoreCase("PNG"))throw new IllegalArgumentException("JPG 또는 PNG 이미지만 사용할 수 있습니다.");if(reader.getWidth(0)>8000||reader.getHeight(0)>8000||(long)reader.getWidth(0)*reader.getHeight(0)>24000000)throw new IllegalArgumentException("이미지는 가로·세로 8000px 및 2400만 화소 이내여야 합니다.");mime=format.equalsIgnoreCase("JPEG")?"image/jpeg":"image/png";}finally{reader.dispose();}}
            result.add(new ScmService.ImageData(mime,data));
        }return result;
    }
}