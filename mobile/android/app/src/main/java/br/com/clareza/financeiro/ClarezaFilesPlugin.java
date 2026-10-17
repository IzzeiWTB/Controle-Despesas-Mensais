package br.com.clareza.financeiro;

import android.app.Activity;
import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** User-chosen CSV export; no storage permission, cached file or embedded credential. */
@CapacitorPlugin(name = "ClarezaFiles")
public class ClarezaFilesPlugin extends Plugin {
    private volatile boolean pending;

    @PluginMethod
    public void exportCsv(PluginCall call) {
        String filename = call.getString("filename");
        String content = call.getString("content");
        if (!CsvExport.isValid(filename, content)) { call.reject("Invalid CSV export"); return; }
        if (pending) { call.reject("Export already in progress"); return; }
        pending = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try { startActivityForResult(call, intent, "documentCreated"); }
        catch (RuntimeException exception) { pending = false; call.reject("No document picker available"); }
    }

    @ActivityCallback
    private void documentCreated(PluginCall call, ActivityResult result) {
        if (call == null) { pending = false; return; }
        if (result.getResultCode() != Activity.RESULT_OK) {
            pending = false;
            call.resolve(new JSObject().put("saved", false));
            return;
        }
        Intent data = result.getData();
        if (data == null || data.getData() == null || !"content".equals(data.getData().getScheme())) {
            pending = false;
            call.reject("Invalid document destination");
            return;
        }
        // A cloud-backed document provider may block; keep IO off the UI thread.
        getBridge().execute(() -> {
            try (OutputStream stream = getContext().getContentResolver().openOutputStream(data.getData(), "wt")) {
                String content = call.getString("content");
                if (stream == null || !CsvExport.isValid(call.getString("filename"), content)) {
                    call.reject("Unable to open document");
                    return;
                }
                stream.write(content.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            } catch (Exception exception) {
                call.reject("Unable to save CSV");
                return;
            } finally { pending = false; }
            call.resolve(new JSObject().put("saved", true));
        });
    }
}
