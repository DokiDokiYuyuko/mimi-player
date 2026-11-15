package app.mimi.player;

import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;

/** A ticket binds Activity bridge work to the library scope in which it started. */
final class HiddenAccess {
    static final String UNAVAILABLE = "内容不可用";
    private final LibraryStore store;
    private volatile Ticket current = new Ticket(0, false);

    static final class Ticket {
        final long epoch;
        final boolean hidden;

        private Ticket(long epoch, boolean hidden) {
            this.epoch = epoch;
            this.hidden = hidden;
        }
    }

    HiddenAccess(LibraryStore store) {
        this.store = store;
    }

    Ticket ticket() {
        return current;
    }

    Ticket changeScope(boolean hidden) {
        current = new Ticket(current.epoch + 1, hidden);
        return current;
    }

    boolean valid(Ticket ticket) {
        return current == ticket;
    }

    boolean allows(Ticket ticket, String projectId) {
        return valid(ticket) && store.isHidden(projectId) == ticket.hidden;
    }

    void require(Ticket ticket, String projectId) throws IOException {
        if (projectId.isEmpty() || !allows(ticket, projectId)) throw new IOException(UNAVAILABLE);
    }

    boolean coverAllowed(Ticket ticket, String projectId, String name, String epoch) {
        return (!ticket.hidden || Long.toString(ticket.epoch).equals(epoch))
                && allows(ticket, projectId) && store.ownsCover(projectId, name) && valid(ticket);
    }

    void requireCommand(Context context, Ticket ticket, String action, JSONObject payload, String playingProject) throws IOException {
        switch (action) {
            case "ai-control":
            case "ai-draft":
            case "ai-save-draft":
            case "ai-export":
            case "ai-save-beside":
                require(ticket, SubtitleJobs.get(context).jobProject(payload.optString("jobId")));
                if ("ai-save-beside".equals(action)) require(ticket, payload.optString("projectId"));
                break;
            case "play":
            case "subtitles":
            case "subtitle-pref":
            case "ai-start":
            case "convert-audio":
            case "restore-audio":
            case "refresh":
            case "favorite":
            case "remove":
                require(ticket, payload.optString("projectId"));
                break;
            case "toggle":
            case "seek":
            case "previous":
            case "next":
            case "ab":
                if (!playingProject.isEmpty()) require(ticket, playingProject);
                break;
            case "import":
            case "import-files":
                if (ticket.hidden) throw new IOException(HiddenAccess.UNAVAILABLE);
                break;
            default:
                break;
        }
    }

}
