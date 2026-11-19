package ctbrec.ui.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import ctbrec.Config;
import ctbrec.GlobalThreadPool;
import ctbrec.Version;
import ctbrec.io.HttpException;
import ctbrec.io.json.ObjectMapperFactory;
import ctbrec.ui.CamrecApplication;
import ctbrec.ui.controls.Dialogs;
import ctbrec.ui.tabs.TabSelectionListener;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;

import static ctbrec.ErrorMessages.HTTP_RESPONSE_BODY_IS_NULL;
import static ctbrec.io.HttpConstants.USER_AGENT;

@Slf4j
public class NewsTab extends Tab implements TabSelectionListener {
    private static final String ACCESS_TOKEN = "a2804d73a89951a22e0f8483a6fcec8943afd88b7ba17c459c095aa9e6f94fd0";
    private static final String URL = "https://mastodon.cloud/api/v1/accounts/480960/statuses?limit=20&exclude_replies=true";
    private final Config config;
    private final VBox layout = new VBox();

    private final ObjectMapper mapper = ObjectMapperFactory.getMapper();

    public NewsTab(Config config) {
        this.config = config;
        setText("News");
        layout.setMaxWidth(800);
        layout.setAlignment(Pos.CENTER);
        setContent(new ScrollPane(layout));
    }

    @Override
    public void selected() {
        GlobalThreadPool.submit(this::loadToots);
    }

    private void loadToots() {
        try {
            var request = new Request.Builder()
                    .url(URL)
                    .header("Authorization", "Bearer " + ACCESS_TOKEN)
                    .header(USER_AGENT, "ctbrec " + Version.getVersion())
                    .build();
            try (var response = CamrecApplication.httpClient.execute(request)) {
                if (response.isSuccessful()) {
                    var body = Objects.requireNonNull(response.body(), HTTP_RESPONSE_BODY_IS_NULL).string();
                    log.debug(body);
                    if (body.startsWith("[")) {
                        onSuccess(body);
                    } else if (body.startsWith("{")) {
                        onError(body);
                    } else {
                        throw new IOException("Unexpected response: " + body);
                    }
                } else {
                    throw new HttpException(response.code(), response.message());
                }
            }
        } catch (IOException e) {
            log.info("Error while loading news", e);
            Dialogs.showError(getTabPane().getScene(), "News", "Couldn't load news from mastodon", e);
        }
    }

    private void onError(String body) throws IOException {
        var json = new JSONObject(body);
        if (json.has("error")) {
            throw new IOException("Request not successful: " + json.getString("error"));
        } else {
            throw new IOException("Unexpected response: " + body);
        }
    }

    private void onSuccess(String body) throws IOException {
        Status[] statusArray = mapper.readValue(body, Status[].class);
        Platform.runLater(() -> {
            layout.getChildren().clear();
            for (Status status : statusArray) {
                if (status.getInReplyToId() == null && !Objects.equals("direct", status.getVisibility())) {
                    var stp = new StatusPane(status, config.getDateTimeFormatter());
                    layout.getChildren().add(stp);
                    VBox.setMargin(stp, new Insets(10));
                }
            }
        });
    }

    @Override
    public void deselected() {
        // nothing to do
    }
}
