package com.fongmi.android.tv.history;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okio.ByteString;

final class AccountHistoryRemote {
    private final AccountSession.Login login;
    private final String scope;

    AccountHistoryRemote(AccountSession.Login login, String subscription) {
        this.login = login;
        scope = digest(subscription);
    }

    private static String digest(String value) {
        return ByteString.encodeUtf8(value).sha256().hex();
    }

    private JsonObject request(String op, JsonObject data) throws IOException {
        data.addProperty("scope", scope);
        return AccountSession.post(login.server, login.token, op, data);
    }

    JsonObject call(String op, JsonObject local) throws Exception {
        if (op.equals("index")) return index();
        String key = local.get("key").getAsString();
        String id = digest(key);
        String context = scope + "/" + id;
        JsonObject req = new JsonObject();
        req.addProperty("item", id);
        if (op.equals("write")) {
            History row = App.gson().fromJson(local.get("row"), History.class);
            String rev = HistoryPeer.revision(row);
            req.addProperty("expected", local.get("expected").getAsString());
            req.addProperty("revision", rev);
            if (row != null) {
                req.addProperty("label", HistoryCipher.seal(login.dataKey, context, key));
                req.addProperty("payload", HistoryCipher.seal(login.dataKey, context + "/" + rev, local.get("row").toString()));
            }
            return request(op, req);
        }
        JsonObject wire = request("read", req);
        JsonObject result = new JsonObject();
        if (!wire.has("payload")) result.add("row", JsonNull.INSTANCE);
        else {
            String rev = wire.get("revision").getAsString();
            String json = HistoryCipher.open(login.dataKey, context + "/" + rev, wire.get("payload").getAsString());
            result.add("row", JsonParser.parseString(json));
        }
        return result;
    }

    private JsonObject index() throws Exception {
        JsonObject index = new JsonObject();
        String after = "";
        do {
            JsonObject req = new JsonObject();
            req.addProperty("after", after);
            JsonObject page = request("index", req);
            for (var element : page.getAsJsonArray("items")) {
                JsonObject item = element.getAsJsonObject();
                String id = item.get("item").getAsString();
                String key = HistoryCipher.open(login.dataKey, scope + "/" + id, item.get("label").getAsString());
                if (!digest(key).equals(id) || index.size() >= 2000) throw new IOException("账号历史索引异常");
                index.addProperty(key, item.get("revision").getAsString());
            }
            String next = page.get("next").getAsString();
            if (!next.isEmpty() && next.compareTo(after) <= 0) throw new IOException("账号历史分页异常");
            after = next;
        } while (!after.isEmpty());
        JsonObject result = new JsonObject();
        result.add("index", index);
        return result;
    }
}
