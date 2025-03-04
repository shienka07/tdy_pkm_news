import java.io.FileWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {
    public static void main(String[] args) {
        Monitoring monitoring = new Monitoring();
        
        // 환경 변수에서 키워드와 뉴스 제목 가져오기
        String keyword = System.getenv("KEYWORD");
        String newsTitle = System.getenv("NEWS_TITLE");
        
        if (keyword == null || keyword.isEmpty()) {
            System.err.println("KEYWORD 환경변수가 설정되지 않았습니다.");
            return;
        }
        
        if (newsTitle == null || newsTitle.isEmpty()) {
            // 뉴스 제목이 설정되지 않았다면 키워드를 사용
            newsTitle = keyword + " 뉴스";
        }
        
        // 뉴스를 가져오고 Slack에 전송
        monitoring.getNews(keyword, 10, 1, SortType.date, newsTitle);
    }
}

enum SortType {
    sim("sim"), date("date");

    final String value;

    SortType(String value) {
        this.value = value;
    }
}

class Monitoring {
    private final Logger logger;
    private final HttpClient client;
    private final String OUTPUT_FOLDER = "news_data"; // 결과 파일이 저장될 폴더 이름
    private final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>"); // HTML 태그 제거용 정규식

    public Monitoring() {
        logger = Logger.getLogger(Monitoring.class.getName());
        logger.setLevel(Level.INFO);
        logger.info("Monitoring 객체 생성");
        
        // HTTP 클라이언트 생성 (리다이렉트 허용)
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
                
        // 결과 파일 저장 폴더 생성
        createOutputFolder();
    }
    
    // HTML 태그 제거 메서드
    private String removeHtmlTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // HTML 태그 제거
        String result = HTML_TAG_PATTERN.matcher(text).replaceAll("");
        // HTML 엔티티 처리 (예: &quot;, &amp; 등)
        result = result.replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
        return result;
    }
    
    // 결과 파일을 저장할 폴더 생성
    private void createOutputFolder() {
        try {
            Path folderPath = Paths.get(OUTPUT_FOLDER);
            if (!Files.exists(folderPath)) {
                Files.createDirectories(folderPath);
                logger.info(OUTPUT_FOLDER + " 폴더 생성 완료");
            }
        } catch (Exception e) {
            logger.severe("폴더 생성 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 검색어를 통해서 최근 뉴스를 받아오고 슬랙에 전송
    public void getNews(String keyword, int display, int start, SortType sort, String newsTitle) {
        String imageLink = "";
        String[] result = null;
        long timestamp = new Date().getTime();
        
        try {
            // 1. 뉴스 가져오기
            String response = getDataFromAPI("news.json", keyword, display, start, sort);
            String[] tmp = response.split("title\":\"");
            result = new String[display];
            for (int i = 1; i < tmp.length; i++) {
                // HTML 태그 제거하여 저장
                result[i - 1] = removeHtmlTags(tmp[i].split("\",")[0]);
            }
            logger.info(Arrays.toString(result));
            
            // 텍스트 파일로 저장 - Path 객체 사용
            String txtFilename = "%d_%s.txt".formatted(timestamp, keyword);
            Path txtPath = Paths.get(OUTPUT_FOLDER, txtFilename);
            
            // 폴더가 존재하는지 다시 확인
            if (!Files.exists(txtPath.getParent())) {
                Files.createDirectories(txtPath.getParent());
            }
            
            // 텍스트 파일 생성 및 작성
            Files.writeString(txtPath, String.join("\n", Arrays.stream(result)
                    .filter(s -> s != null && !s.isEmpty())
                    .toArray(String[]::new)));
            
            logger.info("텍스트 파일 저장 완료: " + txtPath);
            
            // 2. 이미지 가져오기
            String imageResponse = getDataFromAPI("image", keyword, display, start, SortType.sim);
            imageLink = imageResponse
                    .split("link\":\"")[1].split("\",")[0]
                    .split("\\?")[0]
                    .replace("\\", "");
            logger.info("이미지 링크 " + imageLink);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageLink))
                    .build();
            String[] tmp2 = imageLink.split("\\.");
            String extension = tmp2[tmp2.length - 1].split("\\?")[0];
            String imgFilename = "%d_%s.%s".formatted(timestamp, keyword, extension);
            Path imgPath = Paths.get(OUTPUT_FOLDER, imgFilename);

            HttpResponse<Path> imageResponse2 = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(imgPath));

            logger.info("이미지 다운로드 상태 코드: " + imageResponse2.statusCode());
            logger.info("이미지 저장 경로: " + imageResponse2.body());
            
            // 3. Slack으로 메시지 전송
            sendToSlack(result, newsTitle, imageLink);
            
        } catch (Exception e) {
            logger.severe("오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String getDataFromAPI(String path, String keyword, int display, int start, SortType sort) throws Exception {
        String url = "https://openapi.naver.com/v1/search/%s".formatted(path);
        String params = "query=%s&display=%d&start=%d&sort=%s".formatted(
                URLEncoder.encode(keyword, "UTF-8"), display, start, sort.value
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "?" + params))
                .GET()
                .header("X-Naver-Client-Id", System.getenv("NAVER_CLIENT_ID"))
                .header("X-Naver-Client-Secret", System.getenv("NAVER_CLIENT_SECRET"))
                .build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            logger.info("API 응답 상태 코드: " + response.statusCode());
            return response.body();
        } catch (Exception e) {
            logger.severe("API 연결 오류: " + e.getMessage());
            throw new Exception("연결 에러");
        }
    }
    
    // Slack으로 메시지 전송하는 메서드
    private void sendToSlack(String[] newsItems, String newsTitle, String imageUrl) throws Exception {
        String webhookUrl = System.getenv("SLACK_WEBHOOK_URL");
        
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.severe("SLACK_WEBHOOK_URL 환경변수가 설정되지 않았습니다.");
            return;
        }
        
        // 메시지 내용 빌드
        StringBuilder newsContent = new StringBuilder();
        for (String item : newsItems) {
            if (item != null && !item.isEmpty()) {
                newsContent.append(item).append("\n");
            }
        }
        
        // JSON 페이로드 생성 - 마크다운 코드 블록 없이 plain_text 사용
        String jsonPayload = """
            {
              "blocks": [
                {
                  "type": "header",
                  "text": {
                    "type": "plain_text",
                    "text": "🔴 오늘의 %s 🔴",
                    "emoji": true
                  }
                },
                {
                  "type": "section",
                  "text": {
                    "type": "plain_text",
                    "text": "%s"
                  }
                },
                {
                  "type": "image",
                  "image_url": "%s",
                  "alt_text": "관련 이미지"
                }
              ]
            }
            """.formatted(newsTitle, newsContent.toString(), imageUrl);
        
        // HTTP 요청 생성
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();
                
        // 요청 전송
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            logger.info("Slack 메시지 전송 성공");
        } else {
            logger.severe("Slack 메시지 전송 실패. 상태 코드: " + response.statusCode());
            logger.severe("응답 내용: " + response.body());
        }
    }
}