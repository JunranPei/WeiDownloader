package probe;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProfileProbe {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36";
    private static final Pattern TITLE = Pattern.compile("\\\"displayTitle\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--file")) {
            String html = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
            System.out.println("htmlLength=" + html.length());
            System.out.println("displayTitleCount=" + count(TITLE, html));
            System.out.println("noteIdCount=" + count(Pattern.compile("\\\"noteId\\\"\\s*:\\s*\\\""), html));
            return;
        }
        String userId = args.length > 0 ? args[0] : "5cf0ded3000000001802deeb";
        String token = args.length > 1 ? args[1] : "CBSr-Mhk1hB-_raKQl8cIiDo1MTJfU3Gc6dFkRKibhmIM=";
        String source = args.length > 2 ? args[2] : "pc_user";
        String url = "https://www.xiaohongshu.com/user/profile/" + userId
                + "?channel_type=web_note_detail_r10&parent_page_channel_type=web_profile_board"
                + "&xsec_token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8)
                + "&xsec_source=" + source;
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", UA);
        connection.setRequestProperty("Referer", "https://www.xiaohongshu.com/");
        connection.setRequestProperty("Accept-Encoding", "identity");
        int status = connection.getResponseCode();
        String html;
        try (var stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            html = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.println("status=" + status);
        System.out.println("finalUrl=" + connection.getURL());
        System.out.println("htmlLength=" + html.length());
        System.out.println("initialState=" + html.contains("__INITIAL_STATE__"));
        System.out.println("displayTitleCount=" + count(TITLE, html));
        System.out.println("noteIdCount=" + count(Pattern.compile("\\\"noteId\\\"\\s*:\\s*\\\""), html));
        System.out.println("loginRedirect=" + html.contains("login?redirectPath"));
        Path output = Path.of("build", "profile.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, html, StandardCharsets.UTF_8);
        System.out.println("saved=" + output.toAbsolutePath());
        connection.disconnect();
    }

    private static int count(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) count++;
        return count;
    }
}
