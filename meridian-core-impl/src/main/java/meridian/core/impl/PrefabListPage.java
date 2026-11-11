package meridian.core.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import meridian.core.api.BuilderSelection;
import meridian.core.api.Clipboard;
import meridian.core.api.Pages;

/**
 * The game's prefab list over a module's prefabs - {@code Pages/PrefabListPage.ui}, the window the
 * server opens for an editor on the paste tool's E, driven the way the server drives it: a search
 * field, one row per prefab, a click spins it in the preview beside the list, a double click or
 * Load takes it and closes the window. The keys the rows send back are the server's too ({@code
 * Preview}, {@code Browse}, {@code File}, {@code @SearchQuery}).
 */
final class PrefabListPage implements Pages.Listener {

    private static final String PAGE = "Pages/PrefabListPage.ui";
    private static final String ROW = "Pages/BasicTextButton.ui";

    private final BuilderSelectionImpl tools;
    private final String title;
    private final BuilderSelection.PrefabSource source;
    private final Executor offload;

    private volatile String query = "";
    /** The prefab clicked last - shown in the preview, taken by the Load button. */
    private volatile String previewed;
    /** The rows as last shown. */
    private volatile List<String> shown = List.of();

    PrefabListPage(BuilderSelectionImpl tools, String title, BuilderSelection.PrefabSource source, Executor offload) {
        this.tools = tools;
        this.title = title == null ? "" : title;
        this.source = source;
        this.offload = offload;
    }

    /** Opens the window; the names are asked for here, on the caller's thread. */
    boolean open(Pages pages) {
        List<String> names = names();
        Pages.Page page = pages.open("core.prefabs", true, b -> {
            b.append(PAGE);
            searchField(b);
            persistent(b);
            b.set("#CurrentPath.Text", title);
            fileList(b, names);
            loadButton(b);
        }, this);
        return page != null;
    }

    @Override
    public void data(Pages.Page page, Map<String, String> data) {
        String search = data.get("@SearchQuery");
        if (search != null) {
            query = search;
            offload.execute(() -> {
                List<String> names = names();
                page.update(b -> listing(b, names));
            });
            return;
        }
        if ("~".equals(data.get("File"))) {
            query = "";
            offload.execute(() -> {
                List<String> names = names();
                page.update(b -> {
                    searchField(b);
                    listing(b, names);
                });
            });
            return;
        }
        String name = data.get("Preview");
        if ("true".equals(data.get("Browse"))) {
            String target = name != null ? name : previewed;
            if (target != null && shown.contains(target)) {
                tools.showPrefabPreview(null);
                page.close();
                offload.execute(() -> source.load(target));
            }
            return;
        }
        if (name != null && !name.equals(previewed) && shown.contains(name)) {
            previewed = name;
            List<String> rows = shown;
            page.update(b -> {
                persistent(b);
                fileList(b, rows);
                loadButton(b);
                b.set("#PreviewMessage.Visible", false);
            });
            // A big prefab takes a while to read; the list has answered already.
            offload.execute(() -> showPreview(page, name));
        }
    }

    @Override
    public void dismissed(Pages.Page page) {
        tools.showPrefabPreview(null);
    }

    private void showPreview(Pages.Page page, String name) {
        if (!page.isOpen() || !name.equals(previewed)) {
            return;
        }
        Optional<Clipboard> blocks;
        try {
            blocks = source.preview(name);
        } catch (RuntimeException e) {
            blocks = Optional.empty();
        }
        if (!page.isOpen() || !name.equals(previewed)) {
            return;
        }
        if (blocks.isEmpty() || blocks.get().isEmpty()) {
            tools.showPrefabPreview(null);
            page.update(b -> b.set("#PreviewMessage.Text", "Could not read " + name)
                    .set("#PreviewMessage.Visible", true));
            return;
        }
        tools.showPrefabPreview(blocks.get().blocks());
    }

    private List<String> names() {
        try {
            List<String> all = source.names();
            return all == null ? List.of() : List.copyOf(all);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private void searchField(Pages.Builder b) {
        b.set("#SearchInput.Value", query);
        b.bind(Pages.Trigger.VALUE_CHANGED, "#SearchInput", Map.of("@SearchQuery", "#SearchInput.Value"), false);
    }

    private void persistent(Pages.Builder b) {
        b.bind(Pages.Trigger.ACTIVATING, "#HomeButton", Map.of("File", "~"), true);
    }

    private void listing(Pages.Builder b, List<String> names) {
        persistent(b);
        fileList(b, names);
        b.set("#PreviewMessage.Visible", false);
    }

    private void loadButton(Pages.Builder b) {
        b.set("#LoadButton.Visible", previewed != null);
        b.bind(Pages.Trigger.ACTIVATING, "#LoadButton", Map.of("Browse", "true"), true);
    }

    private void fileList(Pages.Builder b, List<String> names) {
        b.clear("#FileList");
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> rows = names.stream()
                .filter(n -> q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q))
                .toList();
        shown = rows;
        if (rows.isEmpty()) {
            b.appendTo("#FileList", ROW);
            b.set("#FileList[0].Text", q.isEmpty() ? source.emptyText() : "Nothing matches");
            return;
        }
        int i = 0;
        for (String name : rows) {
            String row = "#FileList[" + i + "]";
            b.appendTo("#FileList", ROW);
            b.set(row + ".Text", name);
            b.setRef(row + ".Style", ROW, name.equals(previewed) ? "ActiveLabelStyle" : "SelectedLabelStyle");
            Map<String, String> load = new LinkedHashMap<>();
            load.put("Browse", "true");
            load.put("Preview", name);
            b.bind(Pages.Trigger.ACTIVATING, row, Map.of("Preview", name), false);
            b.bind(Pages.Trigger.DOUBLE_CLICKING, row, load, true);
            i++;
        }
    }
}
