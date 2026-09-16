package com.github.ideanovel.ui;

import com.github.ideanovel.settings.NovelSettingsState;
import com.intellij.ui.components.JBScrollPane;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * 伪装面板：老板键按下后顶替阅读区，看起来像是在编译、看代码或跑终端。
 */
public class DisguisePanel extends javax.swing.JPanel {

    private final JTextArea area = new JTextArea();
    private Timer timer;
    private int lineCursor;
    private List<String> lines = new ArrayList<>();

    public DisguisePanel() {
        setLayout(new BorderLayout());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        add(new JBScrollPane(area), BorderLayout.CENTER);
        rebuild();
    }

    /** 根据设置切到对应的伪装内容 */
    public void rebuild() {
        NovelSettingsState state = NovelSettingsState.getInstance();
        NovelSettingsState.DisguiseMode mode = state == null
                ? NovelSettingsState.DisguiseMode.BUILD_LOG : state.disguise();
        lines = contentOf(mode);
        lineCursor = 0;
        area.setText("");
        appendSome(12);
    }

    public void start() {
        stop();
        timer = new Timer(700, e -> appendSome(1));
        timer.setRepeats(true);
        timer.start();
    }

    public void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    private void appendSome(int n) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (lineCursor >= lines.size()) {
                lineCursor = 0;
            }
            sb.append(lines.get(lineCursor++)).append('\n');
        }
        area.append(sb.toString());
        // 别让文本无限膨胀
        if (area.getDocument().getLength() > 20000) {
            String text = area.getText();
            area.setText(text.substring(text.length() / 2));
        }
        SwingUtilities.invokeLater(() -> area.setCaretPosition(area.getDocument().getLength()));
    }

    private static List<String> contentOf(NovelSettingsState.DisguiseMode mode) {
        List<String> out = new ArrayList<>();
        switch (mode) {
            case CODE:
                out.add("public class OrderServiceImpl implements OrderService {");
                out.add("");
                out.add("    private static final Logger LOG = LoggerFactory.getLogger(OrderServiceImpl.class);");
                out.add("");
                out.add("    @Override");
                out.add("    @Transactional(rollbackFor = Exception.class)");
                out.add("    public OrderDTO createOrder(CreateOrderRequest request) {");
                out.add("        Objects.requireNonNull(request, \"request must not be null\");");
                out.add("        validateInventory(request.getSkuId(), request.getQuantity());");
                out.add("");
                out.add("        Order order = Order.builder()");
                out.add("                .orderNo(idGenerator.nextId())");
                out.add("                .userId(request.getUserId())");
                out.add("                .status(OrderStatus.CREATED)");
                out.add("                .build();");
                out.add("        orderMapper.insert(order);");
                out.add("");
                out.add("        LOG.info(\"order created, orderNo={}, userId={}\", order.getOrderNo(), order.getUserId());");
                out.add("        return OrderConverter.INSTANCE.toDTO(order);");
                out.add("    }");
                out.add("");
                out.add("    private void validateInventory(Long skuId, Integer quantity) {");
                out.add("        if (quantity == null || quantity <= 0) {");
                out.add("            throw new BizException(\"quantity illegal\");");
                out.add("        }");
                out.add("        Inventory inventory = inventoryMapper.selectBySkuId(skuId);");
                out.add("        if (inventory == null || inventory.getAvailable() < quantity) {");
                out.add("            throw new BizException(\"inventory not enough\");");
                out.add("        }");
                out.add("    }");
                out.add("}");
                break;
            case TERMINAL:
                out.add("$ ./gradlew :service:test --tests '*OrderServiceTest*'");
                out.add("> Task :service:compileJava UP-TO-DATE");
                out.add("> Task :service:processResources NO-SOURCE");
                out.add("> Task :service:classes UP-TO-DATE");
                out.add("> Task :service:compileTestJava");
                out.add("> Task :service:test");
                out.add("    OrderServiceTest > shouldCreateOrderWhenInventoryEnough PASSED");
                out.add("    OrderServiceTest > shouldFailWhenInventoryNotEnough PASSED");
                out.add("    OrderServiceTest > shouldRollbackOnException PASSED");
                out.add("BUILD SUCCESSFUL in 8s");
                out.add("4 actionable tasks: 2 executed, 2 up-to-date");
                out.add("$ git status");
                out.add("On branch feature/order-refactor");
                out.add("nothing to commit, working tree clean");
                break;
            case BUILD_LOG:
            default:
                out.add("[INFO] Scanning for projects...");
                out.add("[INFO] ");
                out.add("[INFO] ------------------< com.example:order-service >-------------------");
                out.add("[INFO] Building order-service 1.0-SNAPSHOT                          [1/4]");
                out.add("[INFO] --------------------------------[ jar ]---------------------------------");
                out.add("[INFO] ");
                out.add("[INFO] --- maven-resources-plugin:3.3.1:resources (default-resources) ---");
                out.add("[INFO] Copying 12 resources from src/main/resources to target/classes");
                out.add("[INFO] ");
                out.add("[INFO] --- maven-compiler-plugin:3.11.0:compile (default-compile) ---");
                out.add("[INFO] Changes detected - recompiling the module!");
                out.add("[INFO] Compiling 128 source files to target/classes");
                out.add("Downloading from central: https://repo.maven.apache.org/maven2/org/springframework/spring-core/6.1.2/spring-core-6.1.2.pom");
                out.add("Downloaded from central: https://repo.maven.apache.org/maven2 (12 kB at 340 kB/s)");
                out.add("[INFO] ");
                out.add("[INFO] --- maven-surefire-plugin:3.2.2:test (default-test) ---");
                out.add("[INFO] Running com.example.order.OrderServiceTest");
                out.add("[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0");
                out.add("[INFO] ");
                out.add("[INFO] --- maven-jar-plugin:3.3.0:jar (default-jar) ---");
                out.add("[INFO] Building jar: target/order-service-1.0-SNAPSHOT.jar");
                out.add("[INFO] ------------------------------------------------------------------------");
                out.add("[INFO] BUILD SUCCESS");
                out.add("[INFO] ------------------------------------------------------------------------");
                out.add("[INFO] Total time:  11.842 s");
                out.add("[INFO] Finished at: 2026-09-16T09:28:27+08:00");
                break;
        }
        return out;
    }
}
