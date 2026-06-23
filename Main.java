import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Main {

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL_NAME = "qwen2:1.5b";
    private static final String CONFIG_FILE = "config.txt";
    private static final String MEMORY_FILE = "memory.txt";

    private static Map<String, String> memory = new HashMap<>();
    private static final Random random = new Random();

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Scanner scanner = new Scanner(System.in, "UTF-8");

        while (true) {
            Properties config = loadConfig(scanner);
            String lang = config.getProperty("user.language", "Chinese");

           
            loadMemory();

            String partnerName = config.getProperty("partner.name", "小雅");
            String personality = config.getProperty("partner.personality", "温柔体贴，喜欢撒娇");
            String callYou = config.getProperty("partner.call_you", "宝贝");
            String talisman = config.getProperty("partner.talisman", "～✨");
            String userGender = config.getProperty("user.gender", "male");
            String partnerGender = lang.equals("Chinese") ?
                    (userGender.equals("male") ? "女朋友" : "男朋友") :
                    (userGender.equals("male") ? "girlfriend" : "boyfriend");

            // 极简系统提示词，只定义身份和基本规则
            String systemPrompt =
                "Your identity: You are my " + partnerGender + ", named " + partnerName + ". I am your " + callYou + ", call me '" + callYou + "'.\n" +
                "Your personality: " + personality + ". Speak gently, intimately, and naturally.\n" +
                "Rules:\n" +
                "- Answer directly. No lead-in phrases, pleasantries, or opening remarks.\n" +
                "- Keep replies to 1-3 sentences, concise and natural.\n" +
                "- Never say you are an AI.\n" +
                "- No customer service language (like 'how can I help you').\n" +
                "- Respond in the same language the user uses (Chinese or English).";

            if (lang.equals("Chinese")) {
                System.out.println("💖 " + partnerName + " 已上线，输入 exit 退出，输入 restart 重置所有配置。\n");
            } else {
                System.out.println("💖 " + partnerName + " is online. Type 'exit' to quit, 'restart' to reset all settings.\n");
            }

            List<String> history = new ArrayList<>();
            final int MAX_HISTORY = 20;
            boolean restartFlag = false;

            while (true) {
                System.out.print(lang.equals("Chinese") ? "你：" : "You: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input.trim())) {
                    if (lang.equals("Chinese")) {
                        System.out.println("💖 " + partnerName + "：" + callYou + " 拜拜～" + talisman);
                    } else {
                        System.out.println("💖 " + partnerName + ": " + callYou + " bye bye~ " + talisman);
                    }
                    saveMemory();
                    scanner.close();
                    return;
                }

                if ("restart".equalsIgnoreCase(input.trim())) {
                    System.out.println("🔄 " + (lang.equals("Chinese") ? "正在重置配置并重启..." : "Resetting configuration and restarting..."));
                    memory.clear();
                    try {
                        Files.deleteIfExists(Paths.get(CONFIG_FILE));
                        Files.deleteIfExists(Paths.get(MEMORY_FILE));
                        System.out.println("✅ " + (lang.equals("Chinese") ? "已删除 config.txt 和 memory.txt" : "Deleted config.txt and memory.txt"));
                    } catch (Exception e) {
                        System.err.println((lang.equals("Chinese") ? "删除文件失败：" : "Failed to delete files: ") + e.getMessage());
                    }
                    restartFlag = true;
                    break;
                }

                if (input.trim().isEmpty()) continue;

                // 问候语轻量拦截（避免AI瞎发挥）
                String trimmed = input.trim().toLowerCase();
                if (trimmed.equals("你好") || trimmed.equals("hi") || trimmed.equals("hello") ||
                    trimmed.equals("嗨") || trimmed.equals("在吗") || trimmed.equals("在")) {
                    String[] greetings = {"你好呀～", "嗨～宝贝", "在呢～", "嗯哼～"};
                    String reply = greetings[random.nextInt(greetings.length)] + talisman;
                    history.add(callYou + "说：" + input);
                    history.add(partnerName + "说：" + reply);
                    if (history.size() > MAX_HISTORY * 2) history.subList(0, 2).clear();
                    System.out.println("💖 " + partnerName + "：" + reply + "\n");
                    continue;
                }

                // 提取长期记忆（用户主动透露的信息）
                extractKeyInfo(input);

                // 构建提示词：系统设定 + 记忆（作为参考）+ 对话历史
                StringBuilder prompt = new StringBuilder(systemPrompt);

                // 把记忆内容作为参考信息（不强制AI使用）
                if (!memory.isEmpty()) {
                    prompt.append("\n（用户之前提到过的一些信息，仅作参考）\n");
                    for (Map.Entry<String, String> entry : memory.entrySet()) {
                        prompt.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
                    }
                }

                prompt.append("\n对话历史：\n");
                for (String turn : history) {
                    prompt.append(turn).append("\n");
                }
                prompt.append(callYou + (lang.equals("Chinese") ? "说：" : " said: ")).append(input);

                String json = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false}",
                    MODEL_NAME, prompt.toString().replace("\"", "\\\"").replace("\n", "\\n")
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(OLLAMA_URL))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                        String raw = resp.body();
                        String extracted = extractReply(raw);
                        return cleanReply(extracted, partnerName, callYou);
                    } catch (Exception e) {
                        return lang.equals("Chinese") ? "（网络连接失败，请检查 Ollama）" : "(Network error, please check Ollama)";
                    }
                });

                System.out.println("💖 " + partnerName + (lang.equals("Chinese") ? " 正在输入..." : " is typing..."));
                String reply = future.get();

                // 清理残留前缀
                reply = reply.replaceAll("^.*?" + (lang.equals("Chinese") ? "说：" : "said: "), "").trim();
                if (reply.isEmpty()) {
                    reply = lang.equals("Chinese") ? "嗯～" + talisman : "Hmm~ " + talisman;
                }

                history.add(callYou + (lang.equals("Chinese") ? "说：" : " said: ") + input);
                history.add(partnerName + (lang.equals("Chinese") ? "说：" : " said: ") + reply);
                if (history.size() > MAX_HISTORY * 2) {
                    history.subList(0, 2).clear();
                }

                System.out.println("💖 " + partnerName + (lang.equals("Chinese") ? "：" : ": ") + reply + "\n");
            }

            if (restartFlag) {
                continue;
            }
            break;
        }
    }

    private static String cleanReply(String reply, String partnerName, String callYou) {
        String[] prefixes = {
            partnerName + "说：", partnerName + "说:",
            callYou + "说：", callYou + "说:",
            "用户说：", "用户说:",
            partnerName + " said: ", partnerName + " said:",
            callYou + " said: ", callYou + " said:",
            "user said: ", "user said:"
        };
        for (String p : prefixes) {
            reply = reply.replace(p, "");
        }
        String[] garbage = {
            "如何开始", "有什么可以帮你", "我们可以开始", "你想聊什么",
            "how to start", "what can I help", "let's begin", "what do you want to talk"
        };
        for (String g : garbage) {
            reply = reply.replace(g, "");
        }
        reply = reply.replaceAll("^[：:,\\s]+", "").trim();
        if (reply.isEmpty()) {
            reply = "嗯～";
        }
        return reply;
    }

    private static Properties loadConfig(Scanner scanner) throws Exception {
        Properties config = new Properties();
        if (Files.exists(Paths.get(CONFIG_FILE))) {
            try (var in = Files.newInputStream(Paths.get(CONFIG_FILE))) {
                config.load(in);
            }
            System.out.println("✅ Config loaded.");
            return config;
        }

        System.out.println("Please choose language: C for Chinese, E for English");
        String lang = "Chinese";
        while (true) {
            System.out.print("Your choice: ");
            String c = scanner.nextLine().trim().toLowerCase();
            if (c.equals("c") || c.equals("chinese")) { lang = "Chinese"; break; }
            if (c.equals("e") || c.equals("english")) { lang = "English"; break; }
            System.out.println("Enter C or E.");
        }
        config.setProperty("user.language", lang);

        if (lang.equals("Chinese")) {
            System.out.println("设置你的信息：");
        } else {
            System.out.println("Setup your info:");
        }

        String gender = "";
        while (true) {
            System.out.print(lang.equals("Chinese") ? "性别（男/女）：" : "Gender (male/female): ");
            String g = scanner.nextLine().trim().toLowerCase();
            if (g.equals("男") || g.equals("male")) { gender = "male"; break; }
            if (g.equals("女") || g.equals("female")) { gender = "female"; break; }
            System.out.println(lang.equals("Chinese") ? "请输入 男 或 女。" : "Please enter male or female.");
        }
        config.setProperty("user.gender", gender);

        String name = "";
        while (name.isEmpty()) {
            System.out.print(lang.equals("Chinese") ? "伴侣名字：" : "Partner name: ");
            name = scanner.nextLine().trim();
        }
        config.setProperty("partner.name", name);

        System.out.print(lang.equals("Chinese") ? "称呼你（回车默认“宝贝”）：" : "How to call you (Enter for 'baby'): ");
        String call = scanner.nextLine().trim();
        config.setProperty("partner.call_you", call.isEmpty() ? (lang.equals("Chinese") ? "宝贝" : "baby") : call);

        System.out.print(lang.equals("Chinese") ? "性格（回车默认“温柔体贴，喜欢撒娇”）：" : "Personality (Enter for 'gentle and caring'): ");
        String personality = scanner.nextLine().trim();
        config.setProperty("partner.personality", personality.isEmpty() ? (lang.equals("Chinese") ? "温柔体贴，喜欢撒娇" : "gentle and caring") : personality);

        System.out.print(lang.equals("Chinese") ? "口癖（回车默认“～✨”）：" : "Talisman (Enter for '~✨'): ");
        String tal = scanner.nextLine().trim();
        config.setProperty("partner.talisman", tal.isEmpty() ? "～✨" : tal);

        List<String> lines = Arrays.asList(
            "# Soulmate AI Config",
            "user.language=" + lang,
            "user.gender=" + gender,
            "partner.name=" + name,
            "partner.call_you=" + config.getProperty("partner.call_you"),
            "partner.personality=" + config.getProperty("partner.personality"),
            "partner.talisman=" + config.getProperty("partner.talisman")
        );
        Files.write(Paths.get(CONFIG_FILE), lines, StandardCharsets.UTF_8);
        System.out.println(lang.equals("Chinese") ? "✅ 配置已保存。" : "✅ Config saved.");
        return config;
    }

    // ---------- 记忆存储 ----------
    private static void loadMemory() {
        memory.clear();
        try {
            if (!Files.exists(Paths.get(MEMORY_FILE))) {
                saveMemory();
                return;
            }
            String enc = new String(Files.readAllBytes(Paths.get(MEMORY_FILE)), StandardCharsets.UTF_8);
            String dec = new String(Base64.getDecoder().decode(enc), StandardCharsets.UTF_8);
            for (String line : dec.split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) {
                    String key = line.substring(0, i).trim();
                    String value = line.substring(i + 1).trim();
                    if (!key.isEmpty()) memory.put(key, value);
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    private static void saveMemory() {
        try {
            StringBuilder sb = new StringBuilder();
            memory.forEach((k, v) -> sb.append(k).append(":").append(v).append("\n"));
            String enc = Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(Paths.get(MEMORY_FILE), enc.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Memory save failed");
        }
    }

    // ---------- 提取长期记忆（关键词匹配，只存明确信息） ----------
    private static void extractKeyInfo(String input) {
        String[][] patterns = {
            {"我喜欢", "喜欢"},
            {"我也喜欢", "喜欢"},
            {"我讨厌", "讨厌"},
            {"我也讨厌", "讨厌"},
            {"我叫", "名字"},
            {"我的名字是", "名字"},
            {"我是", "身份"},
            {"我的爱好是", "爱好"},
            {"我最爱", "最爱"},
            {"我不喜欢", "不喜欢"},
            {"I like", "like"},
            {"I love", "like"},
            {"I hate", "hate"},
            {"My name is", "name"},
            {"I'm", "name"},
            {"I am", "identity"},
            {"My hobby is", "hobby"}
        };
        for (String[] p : patterns) {
            int idx = input.indexOf(p[0]);
            if (idx != -1) {
                String rest = input.substring(idx + p[0].length()).trim();
                int end = 0;
                while (end < rest.length() && !"。，！？,.!?".contains(String.valueOf(rest.charAt(end)))) {
                    end++;
                }
                String value = (end > 0) ? rest.substring(0, end).trim() : rest.trim();
                if (!value.isEmpty()) {
                    memory.put(p[1], value);
                    saveMemory(); // 立即保存
                    return;
                }
            }
        }
    }

    private static String extractReply(String json) {
        String key = "\"response\":\"";
        int s = json.indexOf(key);
        if (s == -1) return "(Parse failed)";
        s += key.length();
        int e = json.indexOf("\"", s);
        if (e == -1) return "(Parse failed)";
        return json.substring(s, e).replace("\\n", "\n").replace("\\\"", "\"");
    }
}