package pl.bellmarket.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.model.Category;
import pl.bell.hub.api.ActionDef;
import pl.bell.hub.api.ActionField;
import pl.bell.hub.api.ActionResult;
import pl.bell.hub.api.Actor;
import pl.bell.hub.api.BellModule;
import pl.bell.hub.api.HubAction;
import pl.bell.hub.api.MapFilter;
import pl.bell.hub.api.MapMarker;
import pl.bell.hub.api.Stat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Most BellMarket -> panel BellHub. Pelny admin: ekonomia (BellCoins/VIP), ustawienia (jezyk/reload)
 * oraz CRUD kategorii i produktow (pliki YAML). Logika w {@link MarketAdmin} — wola ISTNIEJACE
 * managery / zapisuje YAML w tym samym formacie co GUI. Ladowany leniwie gdy BellHub obecny.
 */
public final class BellHubModule implements BellModule {

    private final BellMarket plugin;
    private final MarketAdmin admin;

    private BellHubModule(BellMarket plugin) {
        this.plugin = plugin;
        this.admin = new MarketAdmin(plugin);
    }

    public static void register(BellMarket plugin) {
        Bukkit.getServicesManager().register(
                BellModule.class, new BellHubModule(plugin), plugin, ServicePriority.Normal);
    }

    @Override public String id() { return "bellmarket"; }
    @Override public String displayName() { return "BellMarket"; }
    @Override public String icon() { return "shopping-cart"; }
    @Override public String permission() { return "bellhub.module.bellmarket"; }

    @Override
    public List<Stat> dashboard() {
        List<Category> categories = plugin.getCategories().getCategories();
        int products = 0;
        for (Category c : categories) products += c.getProducts().size();
        long inCirc = 0;
        CurrencyManager coins = plugin.getCurrency();
        for (Map.Entry<UUID, Long> e : coins.getTopList(Integer.MAX_VALUE)) inCirc += e.getValue();

        List<Stat> stats = new ArrayList<>();
        stats.add(new Stat("Kategorie", Integer.toString(categories.size()), "cyan"));
        stats.add(new Stat("Produkty", Integer.toString(products), "violet"));
        stats.add(new Stat("BellCoins w obiegu", Long.toString(inCirc), "gold"));
        String lang = plugin.getConfig().getString("language", "en");
        stats.add(new Stat("Język", lang.toUpperCase(), "silver"));
        return stats;
    }

    @Override
    public List<MapMarker> markers(MapFilter filter) { return List.of(); }

    @Override
    public String view(String viewId, Map<String, String> params) {
        return switch (viewId) {
            case "player" -> admin.viewPlayer(params.get("player"));
            case "top" -> admin.viewTop();
            case "catalog" -> admin.viewCatalog();
            case "sources" -> admin.viewSources();
            case "providers" -> admin.viewProviders();
            case "settings" -> admin.viewSettings();
            default -> "{}";
        };
    }

    @Override
    public ActionResult invoke(HubAction action, Actor actor) {
        return admin.invoke(action, actor);
    }

    @Override
    public List<ActionDef> actions() {
        return List.of(
                ActionDef.of("coins.set", "Ustaw saldo BellCoins", "BellCoins",
                        ActionField.player("player", "Gracz"), ActionField.number("value", "Kwota")),
                ActionDef.of("coins.give", "Dodaj BellCoins", "BellCoins",
                        ActionField.player("player", "Gracz"), ActionField.number("value", "Kwota")),
                ActionDef.of("coins.take", "Zabierz BellCoins", "BellCoins",
                        ActionField.player("player", "Gracz"), ActionField.number("value", "Kwota")),
                ActionDef.of("vip.set", "Ustaw tokeny VIP", "VIP",
                        ActionField.player("player", "Gracz"), ActionField.number("value", "Liczba")),
                ActionDef.of("vip.give", "Dodaj tokeny VIP", "VIP",
                        ActionField.player("player", "Gracz"), ActionField.number("value", "Liczba")),
                ActionDef.of("vip.take", "Zabierz tokeny VIP", "VIP",
                        ActionField.player("player", "Gracz"), ActionField.number("value", "Liczba")),
                ActionDef.of("settings.language", "Język", "Ustawienia",
                        ActionField.select("value", "Język", List.of("pl", "en"))),
                ActionDef.of("settings.reload", "Przeładuj BellMarket", "Ustawienia"),
                ActionDef.of("provider.setEnabled", "Włącz/wyłącz providera", "Katalog",
                        ActionField.text("id", "Provider (np. elitemobs)"), ActionField.bool("value", "Włączony")),
                ActionDef.of("category.create", "Nowa kategoria", "Katalog",
                        ActionField.text("id", "ID (a-z,0-9)"), ActionField.text("name", "Nazwa"),
                        ActionField.text("icon", "Ikona (material)"), ActionField.number("order", "Slot/kolejność"),
                        ActionField.select("currency", "Waluta", List.of("bellcoins", "viptoken"))),
                ActionDef.of("category.setCurrency", "Waluta kategorii", "Katalog",
                        ActionField.text("id", "ID kategorii"), ActionField.select("value", "Waluta", List.of("bellcoins", "viptoken"))),
                ActionDef.of("category.setIcon", "Ikona kategorii", "Katalog",
                        ActionField.text("id", "ID kategorii"), ActionField.text("value", "Material ikony")),
                ActionDef.of("category.setOrder", "Slot/kolejność kategorii", "Katalog",
                        ActionField.text("id", "ID kategorii"), ActionField.number("value", "Pozycja")),
                ActionDef.of("category.setPermission", "Uprawnienie kategorii", "Katalog",
                        ActionField.text("id", "ID kategorii"), ActionField.text("value", "Permission (puste=brak)")),
                ActionDef.destructive("category.delete", "Usuń kategorię", "Katalog",
                        ActionField.text("id", "ID kategorii")),
                ActionDef.of("product.addExisting", "Dodaj istniejący produkt", "Katalog",
                        ActionField.text("category", "ID kategorii"), ActionField.text("source", "ID produktu źródłowego"),
                        ActionField.number("price", "Cena (puste=oryginalna)")),
                ActionDef.of("product.create", "Nowy produkt (ręczny)", "Katalog",
                        ActionField.text("category", "ID kategorii"), ActionField.text("id", "ID produktu"),
                        ActionField.select("type", "Typ", List.of("ITEM", "COMMAND", "MOUNT", "VIP_EXCLUSIVE")),
                        ActionField.text("name", "Nazwa"), ActionField.number("price", "Cena"),
                        ActionField.text("material", "Material (ITEM)"), ActionField.number("amount", "Ilość (ITEM)"),
                        ActionField.text("commands", "Komendy (COMMAND, po średniku)")),
                ActionDef.of("product.setPrice", "Ustaw cenę produktu", "Katalog",
                        ActionField.text("category", "ID kategorii"), ActionField.text("id", "ID produktu"),
                        ActionField.number("value", "Cena")),
                ActionDef.destructive("product.delete", "Usuń produkt", "Katalog",
                        ActionField.text("category", "ID kategorii"), ActionField.text("id", "ID produktu"))
        );
    }
}
