package burp.api.montoya;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static jdk.internal.net.http.common.Log.logError;
import static sun.font.FontUtilities.logInfo;

public class CrawlergoSender implements BurpExtension, ContextMenuItemsProvider {

    private MontoyaApi api;
    private JTextField exePathField;
    private JTextField chromePathField;
    private JTextField customHeadersField;
    private JTextField postDataField;
    private JTextField extraArgsField;  // 新增参数输入框
    private JTextArea logArea;
    private File logFile;

    // 新增进程管理字段
    private Process currentProcess;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("crawlergoSender");
        initLogFile();

        api.userInterface().registerContextMenuItemsProvider(this);
        api.userInterface().registerSuiteTab("Crawlergo", createUI());
        loadConfig();
    }

    private void browseFile(JTextField targetField, String fileExtension) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("*." + fileExtension, fileExtension));

        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            targetField.setText(selectedFile.getAbsolutePath());
        }
    }

    private JPanel createPathRow(String label, JTextField field, String ext) {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.add(new JLabel(label), BorderLayout.WEST);

        field.setMaximumSize(new Dimension(400, 25));
        JButton browse = new JButton("浏览");
        browse.addActionListener(e -> browseFile(field, ext));

        JPanel fieldPanel = new JPanel(new BorderLayout());
        fieldPanel.add(field, BorderLayout.CENTER);
        fieldPanel.add(browse, BorderLayout.EAST);

        panel.add(fieldPanel, BorderLayout.CENTER);
        return panel;
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(null, message, "错误", JOptionPane.ERROR_MESSAGE));
    }

    private String parseAllHeaders(HttpRequestResponse request) {
        StringBuilder headers = new StringBuilder("{");
        boolean first = true;
        for (HttpHeader header : request.request().headers()) {
            if (!first) {
                headers.append(",");
            }
            // 转义双引号和反斜杠
            String name = header.name().replace("\\", "\\\\").replace("\"", "\\\"");
            String value = header.value().replace("\\", "\\\\").replace("\"", "\\\"");
            headers.append(String.format("\"%s\":\"%s\"", name, value));
            first = false;
        }
        headers.append("}");
        return headers.toString();
    }


    /**
     * 合并用户输入的头和请求中的头
     */
    private String mergeHeaders(String userInput, String requestHeadersJson) {
        Map<String, String> headerMap = new LinkedHashMap<>();

        // 解析来自请求的headers
        if (!requestHeadersJson.isEmpty() && requestHeadersJson.startsWith("{")) {
            parseJsonToMap(requestHeadersJson, headerMap);
        }

        // 解析用户输入（支持两种格式）
        if (!userInput.isEmpty()) {
            if (userInput.trim().startsWith("{")) {
                parseJsonToMap(userInput, headerMap);
            } else {
                parseKeyValueToMap(userInput, headerMap);
            }
        }

        // 生成合并后的JSON
        return buildJsonString(headerMap);
    }

    private void parseKeyValueToMap(String input, Map<String, String> map) {
        String[] parts = input.split(":", 2);
        if (parts.length == 2) {
            String key = parts[0].trim().replace("\"", "");
            String value = parts[1].trim().replace("\"", "");
            map.put(key, value);
        }
    }

    private String buildJsonString(Map<String, String> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) {
                json.append(",");
            }
            // 双重转义：先转义内容中的双引号，再转义整个键值对
            String escapedKey = entry.getKey().replace("\\", "\\\\").replace("\"", "\\\"");
            String escapedValue = entry.getValue().replace("\\", "\\\\").replace("\"", "\\\"");
            json.append(String.format("\\\"%s\\\":\\\"%s\\\"", escapedKey, escapedValue));
            first = false;
        }
        json.append("}");
        return json.toString();
    }

    private void parseJsonToMap(String jsonStr, Map<String, String> map) {
        // 去除外层大括号和转义符
        String cleanJson = jsonStr.trim()
                .replaceAll("^\\{\\\"", "{")
                .replaceAll("\\\"}$", "}")
                .replace("\\\"", "\"");

        // 分割键值对
        String[] pairs = cleanJson.substring(1, cleanJson.length() - 1).split(",\\\"");
        for (String pair : pairs) {
            String[] kv = pair.split(":\"", 2);
            if (kv.length == 2) {
                String key = kv[0].replace("\"", "").trim();
                String value = kv[1].replace("\"", "").trim();
                map.put(key, value);
            }
        }
    }

    /**
     * 将用户输入转换为JSON格式
     */
    private String parseUserHeaders(String userInput) {
        if (userInput.isEmpty()) {
            return "{}";
        }
        userInput = userInput.trim();
        if (userInput.startsWith("{")) {
            return userInput;
        } else {
            String[] parts = userInput.split(":", 2);
            if (parts.length == 2) {
                String name = parts[0].trim();
                String value = parts[1].trim();
                return String.format("{\"%s\":\"%s\"}", name, value.replace("\"", "\\\""));
            } else {
                return "{}";
            }
        }
    }

    private void logOutput(InputStream inputStream) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    // 直接写入文件，不显示到UI
                    writeToLogFile(line);
                }
            } catch (IOException e) {
                logError("输出流读取错误: " + e.getMessage());
            }
        }).start();
    }

    private String quotePath(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    private void addParam(List<String> cmd, String flag, String value) {
        if (value != null && !value.isEmpty()) {
            cmd.add(flag);
            cmd.add(value);
        }
    }

    private String parseHeaders(HttpRequestResponse request) {
        StringBuilder headers = new StringBuilder("{");
        boolean hasHeaders = false;

        for (HttpHeader header : request.request().headers()) {
            if (header.name().equalsIgnoreCase("Cookie") ||
                    header.name().equalsIgnoreCase("Authorization")) {
                if (hasHeaders) {
                    headers.append(",");
                }
                headers.append(String.format("\"%s\":\"%s\"",
                        header.name(),
                        header.value().replace("\"", "\\\""))); // 转义双引号
                hasHeaders = true;
            }
        }
        headers.append("}");
        return hasHeaders ? headers.toString() : "";
    }

    private void initLogFile() {
        try {
            // 获取Burp Suite的安装目录（通过系统属性）
            String burpDir = System.getProperty("user.dir");
            logFile = new File(burpDir, "crawlergo_log.txt");

            if (!logFile.exists()) {
                if (!logFile.createNewFile()) {
                    logError("无法创建日志文件");
                }
            }
            logInfo("日志文件路径: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            logError("日志初始化失败: " + e.getMessage());
        }
    }

    private Component createUI() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // 程序路径
        panel.add(createPathRow("crawlergo路径:", exePathField = new JTextField(30), "exe"));
        panel.add(Box.createVerticalStrut(5));
        panel.add(createPathRow("Chrome路径:", chromePathField = new JTextField(30), "exe"));

        // 参数设置
        JPanel paramPanel = new JPanel(new GridLayout(0, 2, 5, 2));
        paramPanel.add(new JLabel("自定义请求头:"));
        customHeadersField = new JTextField();
        paramPanel.add(customHeadersField);
//        paramPanel.add(new JLabel("POST数据:"));
//        postDataField = new JTextField();
//        paramPanel.add(postDataField);
        paramPanel.add(new JLabel("额外参数:"));  // 新增参数行
        extraArgsField = new JTextField();
        paramPanel.add(extraArgsField);

        panel.add(paramPanel);
        panel.add(Box.createVerticalStrut(10));

        // 日志区域
        logArea = new JTextArea(15, 60);
        logArea.setEditable(false);
        panel.add(new JScrollPane(logArea));

        // 操作按钮
        JButton saveBtn = new JButton("保存配置");
        saveBtn.addActionListener(e -> saveConfig());
        panel.add(saveBtn);

        return new JScrollPane(panel);
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        // 只要选中请求即显示菜单项
        if (!event.selectedRequestResponses().isEmpty()) {
            JMenuItem item = new JMenuItem("Send to crawlergo");
            item.addActionListener(e -> handleRequest(event.selectedRequestResponses()));
            return List.of(item);
        }
        return List.of();
    }

    private void logInfo(String message) {
        logInfo(message, false);
    }

    private void logInfo(String message, boolean isCommand) {
        SwingUtilities.invokeLater(() -> {
            if (isCommand) {
                logArea.append("[INFO] " + message + "\n");
            }
            writeToLogFile("[INFO] " + message);
        });
    }

    private void logError(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[ERROR] " + message + "\n");
            writeToLogFile("[ERROR] " + message);
        });
    }
    private void writeToLogFile(String content) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(content + System.lineSeparator()); // 系统级换行
        } catch (IOException e) {
            logError("文件写入失败: " + e.getMessage());
        }
    }

    private void handleRequest(List<HttpRequestResponse> selected) {
        if (selected.isEmpty()) {
            showError("请先选择请求");
            return;
        }

        HttpRequestResponse request = selected.get(0);
        if (request.request() == null) {
            showError("无效请求");
            return;
        }

        new Thread(() -> executeRequest(request)).start();
    }

    private void executeRequest(HttpRequestResponse request) {
        executor.execute(() -> {
            try {
                // 终止前一个进程
                if (currentProcess != null && currentProcess.isAlive()) {
                    currentProcess.destroyForcibly();
                    Thread.sleep(1000);
                }

                String url = request.request().url();
                List<String> command = buildCommand(url, request);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);  // 合并错误流
                currentProcess = pb.start();

                // 异步读取输出
                new Thread(() -> logOutput(currentProcess.getInputStream())).start();

                int exitCode = currentProcess.waitFor();
                logInfo("进程退出码: " + exitCode);

            } catch (Exception ex) {
                logError("执行错误: " + ex.getMessage());
            } finally {
                currentProcess = null;
            }
        });
    }

    private List<String> buildCommand(String url, HttpRequestResponse request) {
        List<String> cmd = new ArrayList<>();

        // 1. 主程序路径
        cmd.add(quotePath(exePathField.getText().trim()));

        // 2. 额外参数
        if (!extraArgsField.getText().trim().isEmpty()) {
            cmd.addAll(splitArguments(extraArgsField.getText().trim()));
        }

        // 3. 浏览器路径
        addParam(cmd, "-c", chromePathField.getText().trim());

        // 4. 合并用户输入和请求头
        String requestHeaders = parseAllHeaders(request);
        String mergedHeaders = mergeHeaders(customHeadersField.getText().trim(), requestHeaders);

        if (!mergedHeaders.equals("{}")) {
            cmd.add("--custom-headers");
            cmd.add("\"" + mergedHeaders + "\""); // 添加外层引号保持格式
        }

        // 5. POST数据
//        addParam(cmd, "-d", postDataField.getText().trim());

        // 6. URL必须最后添加
        cmd.add(url);

        logInfo("完整命令: " + String.join(" ", cmd), true);
        return cmd;
    }

    private String quoteParam(String param) {
        return "\"" + param.replace("\"", "\\\"") + "\"";
    }

    private List<String> splitArguments(String args) {
        List<String> arguments = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean inQuotes = false;

        for (char c : args.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!currentArg.isEmpty()) {
                    arguments.add(currentArg.toString());
                    currentArg.setLength(0);
                }
            } else {
                currentArg.append(c);
            }
        }
        if (!currentArg.isEmpty()) {
            arguments.add(currentArg.toString());
        }
        return arguments;
    }

    private void saveConfig() {
        PersistedObject persisted = api.persistence().extensionData();
        persisted.setString("exePath", exePathField.getText());
        persisted.setString("chromePath", chromePathField.getText());
        persisted.setString("headers", customHeadersField.getText());
        persisted.setString("postData", postDataField.getText());
        persisted.setString("extraArgs", extraArgsField.getText());  // 保存额外参数
    }

    private void loadConfig() {
        PersistedObject persisted = api.persistence().extensionData();
        exePathField.setText(getConfig("exePath"));
        chromePathField.setText(getConfig("chromePath"));
        customHeadersField.setText(getConfig("headers"));
        postDataField.setText(getConfig("postData"));
        extraArgsField.setText(getConfig("extraArgs"));  // 加载额外参数
    }

    private String getConfig(String key) {
        String value = api.persistence().extensionData().getString(key);
        return value != null ? value : "";
    }
}