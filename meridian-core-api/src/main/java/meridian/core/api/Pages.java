package meridian.core.api;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Opens windows on the client - the interactive pages the server uses for its own lists and
 * forms - without the server knowing.
 *
 * <p>A page is built from {@code .ui} documents the client already has (the game's own, such as
 * {@code Pages/PrefabListPage.ui}) and set up by commands; clicks and typing come back as data
 * events, each a small map from the keys the page's bindings named. The server is kept out of it
 * entirely: what the client sends about our page never reaches it, and its own bookkeeping of
 * acknowledgements - which it holds the player to strictly - stays as if nothing had been open.
 *
 * <pre>{@code
 * Pages pages = ctx.services().require(Pages.class);
 * pages.open("my.list", true, p -> p
 *         .append("Pages/PrefabListPage.ui")
 *         .bind(Pages.Trigger.ACTIVATING, "#HomeButton", Map.of("File", "~"), true),
 *     new Pages.Listener() {
 *         public void data(Pages.Page page, Map<String, String> data) { ... page.update(...); }
 *     });
 * }</pre>
 */
public interface Pages {

    /**
     * Opens a page, replacing whatever page is up.
     *
     * @param key        a name for the page; the client only uses it to tell pages apart
     * @param canDismiss whether the player may close it (Esc, the back button)
     * @param build      the page's first contents
     * @param listener   hears the page's events
     * @return the page, or null when there is no client to show it to
     */
    Page open(String key, boolean canDismiss, Consumer<Builder> build, Listener listener);

    /** What a binding listens for. */
    enum Trigger { ACTIVATING, DOUBLE_CLICKING, RIGHT_CLICKING, VALUE_CHANGED }

    /** An open page of ours. */
    interface Page {

        /**
         * Changes the page. Every data event is answered with one - the client may hold the page
         * locked until it is - so an event the listener does not answer gets an empty one.
         */
        void update(Consumer<Builder> build);

        /** Closes the page. Nothing happens if it is already gone. */
        void close();

        /** Whether the page is still up - not closed, dismissed or replaced by another. */
        boolean isOpen();
    }

    /** Commands and event bindings for a page; every method returns {@code this}. */
    interface Builder {

        /** Appends a {@code .ui} document the client has, at the root. */
        Builder append(String documentPath);

        /** Appends a {@code .ui} document inside {@code selector}. */
        Builder appendTo(String selector, String documentPath);

        Builder set(String selector, String value);

        Builder set(String selector, int value);

        Builder set(String selector, boolean value);

        /**
         * Sets a property to a value named in a document - a style, say:
         * {@code setRef("#List[0].Style", "Pages/BasicTextButton.ui", "ActiveLabelStyle")}.
         */
        Builder setRef(String selector, String documentPath, String valueName);

        /** Removes every child of the node {@code selector} points at. */
        Builder clear(String selector);

        Builder remove(String selector);

        /**
         * Binds an event. {@code data} is what the event sends back; a key that starts with
         * {@code @} takes its value from the element its value names when the event fires
         * ({@code "@SearchQuery" -> "#SearchInput.Value"}).
         *
         * @param locks whether the client holds the page until our answer comes
         */
        Builder bind(Trigger trigger, String selector, Map<String, String> data, boolean locks);
    }

    /** What happens on a page of ours. */
    interface Listener {

        /** A bound event fired, with the data its binding named. Answer with {@link Page#update}. */
        void data(Page page, Map<String, String> data);

        /** The player closed the page, or something else took its place. */
        default void dismissed(Page page) {}
    }
}
