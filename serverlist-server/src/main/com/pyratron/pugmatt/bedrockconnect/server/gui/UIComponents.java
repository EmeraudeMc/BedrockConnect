package main.com.pyratron.pugmatt.bedrockconnect.server.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import main.com.pyratron.pugmatt.bedrockconnect.BedrockConnect;
import main.com.pyratron.pugmatt.bedrockconnect.server.BCPlayer;

import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class UIComponents {

    // Compiled once instead of on every String.matches() call
    private static final Pattern IPV4 = Pattern.compile("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$");
    private static final Pattern HOSTNAME = Pattern.compile("^((?!-)[A-Za-z0-9_-]{1,63}(?<!-)\\.)+[A-Za-z]{2,64}$");
    private static final Pattern PORT = Pattern.compile("[0-9]+");
    private static final Pattern DISPLAY_NAME = Pattern.compile("^[a-zA-Z0-9]+( +[a-zA-Z0-9]+)*$");
    private static final Pattern DOMAIN = Pattern.compile("(?![\\d.]+)((?!-))(xn--)?[a-z0-9][a-z0-9-_]{0,61}[a-z0-9]{0,1}\\.(xn--)?([a-z0-9\\._-]{1,61}|[a-z0-9-]{1,30})");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    public static JsonObject createLabel(String text) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "label");
        obj.addProperty("text", text);
        return obj;
    }

    public static JsonObject createInput(String text, String placeholder) {
        JsonObject input = new JsonObject();
        input.addProperty("type", "input");
        input.addProperty("placeholder", placeholder);
        input.addProperty("text", text);
        return input;
    }

    public static JsonObject createInput(String text, String placeholder, String defaultValue) {
        JsonObject input = new JsonObject();
        input.addProperty("type", "input");
        input.addProperty("placeholder", placeholder);
        input.addProperty("text", text);
        input.addProperty("default", defaultValue);
        return input;
    }

    public static JsonObject createButton(String text, String image, String imageType) {
        JsonObject button = new JsonObject();
        button.addProperty("type", "button");
        button.addProperty("text", text);
        if(image != null && imageType != null) {
            JsonObject buttonImage = new JsonObject();
            buttonImage.addProperty("type", imageType);
            buttonImage.addProperty("data", image);
            button.add("image", buttonImage);
        }
        return button;
    }

    public static JsonObject createButton(String text) {
        JsonObject button = new JsonObject();
        button.addProperty("type", "button");
        button.addProperty("text", text);
        return button;
    }

    public static JsonObject createToggle(String text) {
        JsonObject toggle = new JsonObject();
        toggle.addProperty("type", "toggle");
        toggle.addProperty("text", text);
        toggle.addProperty("defaultValue", false);
        return toggle;
    }

    public static JsonObject createToggle(String text, boolean toggled) {
        JsonObject toggle = new JsonObject();
        toggle.addProperty("type", "toggle");
        toggle.addProperty("text", text);
        toggle.addProperty("defaultValue", toggled);
        return toggle;
    }

    public static JsonObject createForm(String type, String title) {
        JsonObject form = new JsonObject();
        form.addProperty("type", type);
        form.addProperty("title", title);
        return form;
    }

    public static JsonObject createDropdown(List<String> options, String title, String defaultIndex) {
        JsonObject dropdown = new JsonObject();
        dropdown.addProperty("type", "dropdown");
        JsonArray servers = new JsonArray(options.size());
        for(String option : options) {
            servers.add(option);
        }
        dropdown.add("options", servers);
        dropdown.addProperty("text", title);
        dropdown.addProperty("defaultOptionIndex", defaultIndex);
        return dropdown;
    }

    public static String serversToFormData(List<String> list) {
        // Same ["a","b"] format as before, but built in one pass and with proper JSON escaping
        return JSONArray.toJSONString(list);
    }

    public static ArrayList<String> getFormData(String data) {
        JSONParser parser = new JSONParser();

        // If no server data
        if(data == null)
            return new ArrayList<>();

        try {
            JSONArray obj = (JSONArray) parser.parse(data);
            ArrayList<String> strings = new ArrayList<>(obj.size());
            for(Object o : obj) {
                strings.add(String.valueOf(o));
            }
            return strings;
        } catch(ParseException e) {
            BedrockConnect.logger.error("Error parsing form data", e);
        }

        return null;
    }

    public static boolean validateServerInfo(String address, String port, String name, BCPlayer player) {
        if(address.length() >= 253)
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "addressLarge"));
        else if(port.length() >= 10)
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "portLarge"));
        else if(name.length() >= 36)
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "nameLarge"));
        else if (!IPV4.matcher(address).matches() && !HOSTNAME.matcher(address).matches())
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "invalidAddress"));
        else if (!PORT.matcher(port).matches())
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "invalidPort"));
        else if (!name.isEmpty() && !DISPLAY_NAME.matcher(name).matches())
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "invalidName"));
        else
            return true;
        return false;
    }

    public static boolean isDomain(String address) {
        return DOMAIN.matcher(address).matches();
    }

    public static String[] validateAddress(String server, BCPlayer player) {
        String[] parts = server.split(":");
        if (parts.length > 1) {
            return parts;
        } else {
            player.createError((BedrockConnect.getConfig().getLanguage().getWording("error", "invalidUserServer")));
        }
        return null;
    }

    public static ArrayList<String> cleanAddress(ArrayList<String> data) {
        data.set(0, WHITESPACE.matcher(data.get(0)).replaceAll(""));
        data.set(1, WHITESPACE.matcher(data.get(1)).replaceAll(""));
        return data;
    }

    /**
     * If server display name is included in server data, return this value
     * If not stored, use server address as display name
     * @param server
     * @return
     */
    public static String getServerDisplayName(String server) {
        String displayName;
        String[] serverParts = server.split(":");
        if(serverParts.length > 2)
            displayName = serverParts[2];
        else
            displayName = server;
        return displayName;
    }
}
