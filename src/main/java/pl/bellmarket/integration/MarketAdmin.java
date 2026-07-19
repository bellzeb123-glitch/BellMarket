package pl.bellmarket.integration;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import pl.bellmarket.BellMarket;
import pl.bellmarket.currency.CurrencyManager;
import pl.bellmarket.currency.VipTokenManager;
import pl.bellmarket.model.Category;
import pl.bellmarket.model.Product;
import pl.bellmarket.provider.ProductProvider;
import pl.bell.hub.api.ActionResult;
import pl.bell.hub.api.Actor;
import pl.bell.hub.api.HubAction;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Logika admina BellMarket dla panelu BellHub. Ekonomia woła managery; struktura sklepu
 * (kategorie/produkty manualne) = pliki YAML categories/; ceny produktow providerow =
 * override w providers/&lt;source&gt;.yml. Po kazdym zapisie plugin.reload(). Zero duplikacji.
 */
public final class MarketAdmin {

    private final BellMarket plugin;

    public MarketAdmin(BellMarket plugin) { this.plugin = plugin; }

    private CurrencyManager coins() { return plugin.getCurrency(); }
    private VipTokenManager vip() { return plugin.getVipTokens(); }
    private File catsDir() { return new File(plugin.getDataFolder(), "categories"); }
    private File catFile(String id) { return new File(catsDir(), id + ".yml"); }
    private boolean isManual(String catId) { return catFile(catId).exists(); }

    // ── WIDOKI ──────────────────────────────────────────────

    public String viewPlayer(String name) {
        if (name == null || name.isBlank()) return "{\"found\":false}";
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        UUID uuid = op.getUniqueId();
        return "{\"found\":true,\"player\":\"" + esc(op.getName() != null ? op.getName() : name) + "\","
                + "\"uuid\":\"" + uuid + "\",\"coins\":" + coins().getBalance(uuid)
                + ",\"vip\":" + vip().getBalance(uuid) + "}";
    }

    public String viewTop() {
        List<String> tc = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : coins().getTopList(10))
            tc.add("{\"player\":\"" + esc(coins().getPlayerName(e.getKey())) + "\",\"value\":" + e.getValue() + "}");
        List<String> tv = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : vip().getTopList(10))
            tv.add("{\"player\":\"" + esc(coins().getPlayerName(e.getKey())) + "\",\"value\":" + e.getValue() + "}");
        return "{\"coins\":" + arr(tc) + ",\"vip\":" + arr(tv) + "}";
    }

    public String viewSettings() {
        return "{\"language\":\"" + esc(plugin.getConfig().getString("language", "en"))
                + "\",\"purchasesEnabled\":" + plugin.getConfig().getBoolean("shop.purchases-enabled", true) + "}";
    }

    /** Pelny katalog: WSZYSTKIE kategorie (manualne z PLIKOW — takze wylaczone! + providerow z pamieci)
     *  z produktami, cenami i waluta (BellCoins / VIP token). */
    public String viewCatalog() {
        List<String> out = new ArrayList<>();
        java.util.Set<String> manualIds = new java.util.HashSet<>();

        // Manualne — z plikow (wlacza tez enabled:false, ktorych getCategories() NIE zwraca).
        File[] files = catsDir().listFiles((dd, n) -> n.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                manualIds.add(id);
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection cat = cfg.getConfigurationSection("category");
                String name = cat != null ? cat.getString("name", id) : id;
                int order = cat != null ? cat.getInt("order", 0) : 0;
                boolean enabled = cat == null || cat.getBoolean("enabled", true);
                String perm = cat != null ? cat.getString("required-permission", "") : "";
                String icon = cat != null && cat.getConfigurationSection("icon") != null
                        ? cat.getConfigurationSection("icon").getString("material", "CHEST") : "CHEST";
                String defCur = curName(cat != null ? cat.getString("default-currency", null) : null);

                List<String> prods = new ArrayList<>();
                ConfigurationSection ps = cfg.getConfigurationSection("products");
                if (ps != null) for (String pid : ps.getKeys(false)) {
                    ConfigurationSection p = ps.getConfigurationSection(pid);
                    if (p == null) continue;
                    prods.add("{\"id\":\"" + esc(pid) + "\",\"name\":\"" + esc(stripColor(p.getString("name", pid)))
                            + "\",\"type\":\"" + esc(p.getString("type", "ITEM")) + "\",\"price\":" + p.getLong("price", 0)
                            + ",\"enabled\":" + p.getBoolean("enabled", true) + ",\"source\":\"manual\""
                            + ",\"currency\":\"" + curName(p.getString("currency", null)) + "\"}");
                }
                out.add("{\"id\":\"" + esc(id) + "\",\"name\":\"" + esc(stripColor(name)) + "\",\"icon\":\"" + esc(icon)
                        + "\",\"order\":" + order + ",\"enabled\":" + enabled + ",\"permission\":\"" + esc(perm != null ? perm : "")
                        + "\",\"manual\":true,\"currency\":\"" + defCur + "\",\"products\":" + arr(prods) + "}");
            }
        }
        // Providerow — z pamieci (bez pliku), read-only struktura.
        for (Category c : plugin.getCategories().getCategories()) {
            if (manualIds.contains(c.getId())) continue;
            String perm = plugin.getCategories().getRequiredPermission(c.getId());
            List<String> prods = new ArrayList<>();
            String catCur = "BELLCOINS";
            for (Product p : c.getProducts()) {
                String pc = p.getCurrency() != null ? p.getCurrency().name() : "BELLCOINS";
                if ("VIPTOKEN".equals(pc)) catCur = "VIPTOKEN";
                prods.add("{\"id\":\"" + esc(p.getId()) + "\",\"name\":\"" + esc(stripColor(p.getName()))
                        + "\",\"type\":\"" + p.getType() + "\",\"price\":" + p.getPrice()
                        + ",\"enabled\":" + p.isEnabled() + ",\"source\":\"" + esc(srcOf(p))
                        + "\",\"currency\":\"" + pc + "\"}");
            }
            out.add("{\"id\":\"" + esc(c.getId()) + "\",\"name\":\"" + esc(stripColor(c.getName()))
                    + "\",\"icon\":\"" + esc(c.getIconMaterial().name()) + "\",\"order\":" + c.getOrder()
                    + ",\"enabled\":" + c.isEnabled() + ",\"permission\":\"" + esc(perm != null ? perm : "")
                    + "\",\"manual\":false,\"currency\":\"" + catCur + "\",\"products\":" + arr(prods) + "}");
        }
        // Wylaczone kategorie providerow (enabled:false w pliku) — NIE ma ich w getCategories(),
        // ale pokazujemy je (zwiniete, z przelacznikiem) zeby mozna bylo wlaczyc z powrotem.
        addDisabledProviderCats(out, "skinstudio", "tiers");
        addDisabledProviderCats(out, "elitemobs", "categories");
        addDisabledProviderCats(out, "fmm", "categories");
        addDisabledProviderCats(out, "bellitems", "categories");
        return "{\"categories\":" + arr(out) + "}";
    }

    private void addDisabledProviderCats(List<String> out, String providerId, String sectionPath) {
        File pf = new File(plugin.getDataFolder(), "providers/" + providerId + ".yml");
        if (!pf.exists()) return;
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(pf).getConfigurationSection(sectionPath);
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection sub = sec.getConfigurationSection(key);
            if (sub == null || sub.getBoolean("enabled", true)) continue; // tylko jawnie wylaczone
            String name = sub.getString("display-name", key);
            out.add("{\"id\":\"" + esc(providerId + "_" + key) + "\",\"name\":\"" + esc(stripColor(name))
                    + "\",\"icon\":\"" + esc(sub.getString("icon", "BARRIER")) + "\",\"order\":999,\"enabled\":false"
                    + ",\"permission\":\"\",\"manual\":false,\"currency\":\"BELLCOINS\",\"products\":[]}");
        }
    }

    /** Providery (auto-kategorie) z ich stanem enabled — do wlaczania/wylaczania calej sekcji providera. */
    public String viewProviders() {
        List<String> out = new ArrayList<>();
        for (ProductProvider p : plugin.getProviderRegistry().getAll()) {
            String id = p.getProviderId();
            boolean enabled = plugin.getProviderRegistry().isEnabledInConfig(id) && providerFileEnabled(id);
            int cats = 0;
            for (Category c : plugin.getCategories().getCategories())
                if (c.getId().startsWith(id + "_") || c.getId().equals(id)) cats++;
            out.add("{\"id\":\"" + esc(id) + "\",\"enabled\":" + enabled
                    + ",\"available\":" + p.isAvailable() + ",\"categories\":" + cats + "}");
        }
        return "{\"providers\":" + arr(out) + "}";
    }

    private boolean providerFileEnabled(String id) {
        File f = new File(plugin.getDataFolder(), "providers/" + id + ".yml");
        if (!f.exists()) return true; // brak top-level enabled (np. skinstudio) -> rzadzi config.yml
        return YamlConfiguration.loadConfiguration(f).getBoolean("enabled", true);
    }

    /** Wlacza/wylacza CALA sekcje providera — config.yml (rejestr) + providers/&lt;id&gt;.yml (plik, ktory user widzi). */
    private ActionResult providerSetEnabled(String id, boolean value) {
        String pid = sanitize(id);
        if (pid.isEmpty()) return ActionResult.error("Brak providera.");
        // 1) rejestr w config.yml (uniwersalny gate dla wszystkich providerow)
        plugin.getConfig().set("providers." + pid + ".enabled", value);
        plugin.saveConfig();
        // 2) plik providera (EM/FMM/MythicMobs czytaja swoj top-level enabled)
        File f = new File(plugin.getDataFolder(), "providers/" + pid + ".yml");
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            cfg.set("enabled", value);
            save(cfg, f);
        }
        plugin.reload();
        return ActionResult.ok("Provider " + pid + (value ? " wlaczony." : " wylaczony."));
    }

    /** Normalizuje wartosc waluty z YAML do nazwy enum: VIPTOKEN / BELLCOINS. */
    private static String curName(String raw) {
        if (raw == null || raw.isBlank()) return "BELLCOINS";
        String n = raw.trim().toLowerCase().replaceAll("[\\s_-]", "");
        return (n.startsWith("vip") || n.contains("token")) ? "VIPTOKEN" : "BELLCOINS";
    }

    /** Lista ISTNIEJACYCH produktow (wszystkie zrodla) do dodania do kategorii — pogrupowana po zrodle. */
    public String viewSources() {
        List<String> out = new ArrayList<>();
        for (Category c : plugin.getCategories().getCategories()) {
            for (Product p : c.getProducts()) {
                out.add("{\"id\":\"" + esc(p.getId()) + "\",\"name\":\"" + esc(stripColor(p.getName()))
                        + "\",\"type\":\"" + p.getType() + "\",\"price\":" + p.getPrice()
                        + ",\"source\":\"" + esc(srcOf(p)) + "\",\"category\":\"" + esc(c.getId()) + "\"}");
            }
        }
        return "{\"products\":" + arr(out) + "}";
    }

    // ── AKCJE ───────────────────────────────────────────────

    public ActionResult invoke(HubAction a, Actor actor) {
        if (!actor.admin() && !actor.has("bellhub.module.bellmarket"))
            return ActionResult.error("Brak uprawnien.");
        try {
            return switch (a.name()) {
                case "coins.set" -> coinsSet(a.param("player"), a.param("value"));
                case "coins.give" -> coinsAdd(a.param("player"), a.param("value"), false);
                case "coins.take" -> coinsAdd(a.param("player"), a.param("value"), true);
                case "vip.set" -> vipSet(a.param("player"), a.param("value"));
                case "vip.give" -> vipAdd(a.param("player"), a.param("value"), false);
                case "vip.take" -> vipAdd(a.param("player"), a.param("value"), true);
                case "settings.language" -> setLanguage(a.param("value"));
                case "settings.reload" -> { plugin.reload(); yield ActionResult.ok("BellMarket przeladowany."); }
                case "settings.purchasesEnabled" -> setPurchasesEnabled("true".equalsIgnoreCase(a.param("value")));
                case "provider.setEnabled" -> providerSetEnabled(a.param("id"), "true".equalsIgnoreCase(a.param("value")));
                case "category.create" -> categoryCreate(a.param("id"), a.param("name"), a.param("icon"), a.param("order"), a.param("currency"));
                case "category.setCurrency" -> categorySet(a.param("id"), "default-currency", "VIPTOKEN".equals(curName(a.param("value"))) ? "viptoken" : "bellcoins");
                case "category.delete" -> categoryDelete(a.param("id"));
                case "category.rename" -> categorySet(a.param("id"), "name", a.param("value"), "display-name", a.param("value"));
                case "category.setEnabled" -> categorySetEnabled(a.param("id"), "true".equalsIgnoreCase(a.param("value")));
                case "category.setPermission" -> categorySet(a.param("id"), "required-permission",
                        a.param("value") == null || a.param("value").isBlank() ? null : a.param("value"));
                case "category.setIcon" -> categorySet(a.param("id"), "icon.material",
                        a.param("value") == null ? "CHEST" : a.param("value").toUpperCase());
                case "category.setOrder" -> categorySet(a.param("id"), "order", (int) parseLong(a.param("value")));
                case "product.create" -> productCreate(a);
                case "product.addExisting" -> productAddExisting(a.param("category"), a.param("source"), a.param("price"));
                case "product.delete" -> productDelete(a.param("category"), a.param("id"));
                case "product.setEnabled" -> productSetEnabled(a.param("category"), a.param("id"), "true".equalsIgnoreCase(a.param("value")));
                case "product.rename" -> productSet(a.param("category"), a.param("id"), "name", a.param("value"));
                case "product.setPrice" -> productSetPrice(a.param("category"), a.param("id"), parseLong(a.param("value")), true);
                case "product.setPrices" -> productSetPrices(a.param("category"), a.param("changes"));
                case "category.setAllPrices" -> categorySetAllPrices(a.param("id"), a.param("mode"), a.param("value"));
                case "product.exclude" -> productExclude(a.param("category"), a.param("id"), "true".equalsIgnoreCase(a.param("value")));
                default -> ActionResult.error("Nieznana akcja: " + a.name());
            };
        } catch (NumberFormatException e) {
            return ActionResult.error("Niepoprawna liczba.");
        } catch (Exception e) {
            return ActionResult.error("Blad: " + e.getMessage());
        }
    }

    // ── ekonomia ──
    private ActionResult coinsSet(String n, String v) {
        UUID u = resolve(n); if (u == null) return ActionResult.error("Brak gracza.");
        long x = parseLong(v); if (x < 0) return ActionResult.error("Saldo ujemne.");
        coins().setBalance(u, x); return ActionResult.ok("Saldo BellCoins " + n + " = " + x + ".");
    }
    private ActionResult coinsAdd(String n, String v, boolean take) {
        UUID u = resolve(n); if (u == null) return ActionResult.error("Brak gracza.");
        long x = parseLong(v); if (x < 0) return ActionResult.error("Podaj liczbe dodatnia.");
        if (take) { if (!coins().takeCoins(u, x)) return ActionResult.error("Za malo BellCoins.");
            return ActionResult.ok("Zabrano " + x + " BellCoins " + n + "."); }
        coins().addCoins(u, x); return ActionResult.ok("Dodano " + x + " BellCoins " + n + ".");
    }
    private ActionResult vipSet(String n, String v) {
        UUID u = resolve(n); if (u == null) return ActionResult.error("Brak gracza.");
        long x = parseLong(v); if (x < 0) return ActionResult.error("Liczba ujemna.");
        vip().setBalance(u, x, "BellHub admin"); return ActionResult.ok("Tokeny VIP " + n + " = " + x + ".");
    }
    private ActionResult vipAdd(String n, String v, boolean take) {
        UUID u = resolve(n); if (u == null) return ActionResult.error("Brak gracza.");
        long x = parseLong(v); if (x < 0) return ActionResult.error("Podaj liczbe dodatnia.");
        if (take) { if (!vip().takeCoins(u, x, "BellHub admin")) return ActionResult.error("Za malo tokenow VIP.");
            return ActionResult.ok("Zabrano " + x + " tokenow VIP " + n + "."); }
        vip().addCoins(u, x, "BellHub admin"); return ActionResult.ok("Dodano " + x + " tokenow VIP " + n + ".");
    }

    private ActionResult setLanguage(String code) {
        if (!"pl".equals(code) && !"en".equals(code)) return ActionResult.error("Jezyk: pl lub en.");
        plugin.getConfig().set("language", code); plugin.saveConfig(); plugin.reload();
        return ActionResult.ok("Jezyk = " + code + ".");
    }

    private ActionResult setPurchasesEnabled(boolean value) {
        plugin.getConfig().set("shop.purchases-enabled", value);
        plugin.saveConfig();
        return ActionResult.ok(value ? "Zakupy wlaczone." : "Zakupy wylaczone (przegladanie OK).");
    }

    // ── kategorie ──
    private ActionResult categoryCreate(String id, String name, String icon, String order, String currency) {
        String cid = sanitize(id);
        if (cid.isEmpty()) return ActionResult.error("Podaj poprawne id (a-z, 0-9, _ -).");
        File f = catFile(cid);
        if (f.exists()) return ActionResult.error("Kategoria o tym id juz istnieje.");
        YamlConfiguration cfg = new YamlConfiguration();
        String nm = name == null || name.isBlank() ? cid : name;
        cfg.set("category.name", nm);
        cfg.set("category.display-name", nm);
        cfg.set("category.order", order != null && !order.isBlank() ? (int) parseLong(order) : 99);
        cfg.set("category.enabled", true);
        cfg.set("category.icon.material", icon != null && !icon.isBlank() ? icon.toUpperCase() : "CHEST");
        cfg.set("category.default-currency", "VIPTOKEN".equals(curName(currency)) ? "viptoken" : "bellcoins");
        cfg.createSection("products");
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload();
        return ActionResult.ok("Utworzono kategorie " + cid + " (" + ("VIPTOKEN".equals(curName(currency)) ? "VIP token" : "BellCoins") + ").");
    }

    /** Domyslna waluta kategorii (z pliku) — dziedziczona przez nowe produkty. */
    private String categoryCurrency(String catId) {
        File f = catFile(sanitize(catId));
        if (!f.exists()) return "bellcoins";
        ConfigurationSection cat = YamlConfiguration.loadConfiguration(f).getConfigurationSection("category");
        return "VIPTOKEN".equals(curName(cat != null ? cat.getString("default-currency", null) : null)) ? "viptoken" : "bellcoins";
    }
    /** Wlacz/wylacz kategorie — manualna (categories/&lt;id&gt;.yml) LUB providera (per-tier/per-category w pliku providera). */
    private ActionResult categorySetEnabled(String id, boolean value) {
        File mf = catFile(sanitize(id));
        if (mf.exists()) { // manualna
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(mf);
            cfg.set("category.enabled", value);
            if (!save(cfg, mf)) return ActionResult.error("Blad zapisu.");
            plugin.reload(); return ActionResult.ok("Sekcja " + id + (value ? " wlaczona." : " wylaczona."));
        }
        // providera: skinstudio_<tier> -> tiers.<tier>.enabled ; elitemobs_/fmm_<key> -> categories.<key>.enabled
        String[] t = providerCatTarget(id);
        if (t == null) return ActionResult.error("Tej kategorii nie da sie przelaczyc tutaj (uzyj przelacznika providera).");
        File pf = new File(plugin.getDataFolder(), "providers/" + t[0]);
        YamlConfiguration cfg = pf.exists() ? YamlConfiguration.loadConfiguration(pf) : new YamlConfiguration();
        cfg.set(t[1] + ".enabled", value);
        if (!save(cfg, pf)) return ActionResult.error("Blad zapisu override providera.");
        plugin.reload();
        return ActionResult.ok("Sekcja " + id + (value ? " wlaczona." : " wylaczona.") + " (" + t[0] + ")");
    }

    /** Mapuje id kategorii providera -> [plik providera, sciezka-do-sekcji-tieru/kategorii]. */
    private String[] providerCatTarget(String catId) {
        if (catId == null) return null;
        if (catId.startsWith("skinstudio_")) return new String[]{"skinstudio.yml", "tiers." + catId.substring("skinstudio_".length())};
        if (catId.startsWith("elitemobs_")) return new String[]{"elitemobs.yml", "categories." + catId.substring("elitemobs_".length())};
        if (catId.startsWith("fmm_")) return new String[]{"fmm.yml", "categories." + catId.substring("fmm_".length())};
        if (catId.startsWith("bellitems_")) return new String[]{"bellitems.yml", "categories." + catId.substring("bellitems_".length())};
        return null;
    }

    private ActionResult categoryDelete(String id) {
        File f = catFile(sanitize(id));
        if (!f.exists()) return ActionResult.error("To nie jest manualna kategoria (brak pliku).");
        if (!f.delete()) return ActionResult.error("Nie udalo sie usunac pliku.");
        plugin.reload(); return ActionResult.ok("Usunieto kategorie " + id + ".");
    }
    private ActionResult categorySet(String id, String key, Object value) {
        File f = catFile(sanitize(id));
        if (!f.exists()) return ActionResult.error("Tylko manualne kategorie sa edytowalne.");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        cfg.set("category." + key, value);
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload(); return ActionResult.ok("Kategoria " + id + ": " + key + " zaktualizowane.");
    }
    private ActionResult categorySet(String id, String k1, Object v1, String k2, Object v2) {
        File f = catFile(sanitize(id));
        if (!f.exists()) return ActionResult.error("Tylko manualne kategorie sa edytowalne.");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        cfg.set("category." + k1, v1); cfg.set("category." + k2, v2);
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload(); return ActionResult.ok("Kategoria " + id + " zaktualizowana.");
    }

    // ── produkty ──
    private ActionResult productCreate(HubAction a) {
        String cat = sanitize(a.param("category")), pid = sanitize(a.param("id"));
        if (cat.isEmpty() || pid.isEmpty()) return ActionResult.error("Podaj kategorie i id produktu.");
        File f = catFile(cat);
        if (!f.exists()) return ActionResult.error("Kategoria nie istnieje (tylko manualne).");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (cfg.contains("products." + pid)) return ActionResult.error("Produkt o tym id juz istnieje.");
        String type = a.param("type"); if (type == null || type.isBlank()) type = "ITEM"; type = type.toUpperCase();
        String base = "products." + pid + ".";
        cfg.set(base + "type", type);
        cfg.set(base + "name", a.param("name") != null && !a.param("name").isBlank() ? a.param("name") : pid);
        cfg.set(base + "price", parseLong(a.param("price")));
        cfg.set(base + "enabled", true);
        cfg.set(base + "currency", categoryCurrency(cat)); // dziedziczy walute sekcji (BellCoins / VIP token)
        String icon = a.param("icon");
        cfg.set(base + "icon.material", icon != null && !icon.isBlank() ? icon.toUpperCase() : "PAPER");
        switch (type) {
            case "ITEM" -> {
                String mat = a.param("material");
                cfg.set(base + "item.material", mat != null && !mat.isBlank() ? mat.toUpperCase() : "STONE");
                long amt = a.param("amount") != null && !a.param("amount").isBlank() ? parseLong(a.param("amount")) : 1;
                cfg.set(base + "item.amount", (int) Math.max(1, amt));
            }
            case "COMMAND", "MOUNT", "VIP_EXCLUSIVE" -> {
                List<String> list = new ArrayList<>();
                String cmds = a.param("commands");
                if (cmds != null) for (String c : cmds.split("\\r?\\n|;")) if (!c.isBlank()) list.add(c.trim());
                cfg.set(base + "commands", list);
            }
            default -> { }
        }
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload(); return ActionResult.ok("Dodano produkt " + pid + " do " + cat + ".");
    }

    /** Dodaje do manualnej kategorii produkt SKOPIOWANY z istniejacego (manual/EM/FMM/BellItems/SkinStudio). */
    private ActionResult productAddExisting(String cat, String sourceId, String priceOverride) {
        String c = sanitize(cat);
        if (c.isEmpty() || sourceId == null || sourceId.isBlank()) return ActionResult.error("Podaj kategorie i produkt zrodlowy.");
        File f = catFile(c);
        if (!f.exists()) return ActionResult.error("Kategoria docelowa musi byc manualna (utworz ja w panelu).");
        Product src = findProduct(sourceId);
        if (src == null) return ActionResult.error("Nie znaleziono produktu zrodlowego.");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        String pid = sanitize(src.getId());
        if (cfg.contains("products." + pid)) return ActionResult.error("Taki produkt juz jest w tej kategorii.");
        String base = "products." + pid + ".";
        cfg.set(base + "type", src.getType().name());
        cfg.set(base + "name", src.getName());
        if (src.getLore() != null && !src.getLore().isEmpty()) cfg.set(base + "lore", src.getLore());
        long price = priceOverride != null && !priceOverride.isBlank() ? parseLong(priceOverride) : src.getPrice();
        cfg.set(base + "price", price);
        cfg.set(base + "enabled", true);
        if (src.getIconMaterial() != null) cfg.set(base + "icon.material", src.getIconMaterial().name());
        if (src.getIconItemModel() != null) cfg.set(base + "icon.item-model", src.getIconItemModel());
        if (src.getCurrency() != null) cfg.set(base + "currency", src.getCurrency().name());
        switch (src.getType()) {
            case ITEM -> { if (src.getGiveItem() != null) cfg.set(base + "give-item", src.getGiveItem()); }
            case COMMAND, MOUNT, VIP_EXCLUSIVE -> cfg.set(base + "commands", src.getCommands());
            case SKIN_TOKEN -> { cfg.set(base + "skin-id", src.getSkinId()); cfg.set(base + "include-change-token", src.includeChangeToken()); }
        }
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload();
        return ActionResult.ok("Dodano „" + stripColor(src.getName()) + "” do kategorii " + c + ".");
    }

    private ActionResult productDelete(String cat, String id) {
        File f = catFile(sanitize(cat));
        if (!f.exists()) return ActionResult.error("Produkty providerow usuwa sie wylaczeniem providera, nie tutaj.");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (!cfg.contains("products." + id)) return ActionResult.error("Nie znaleziono produktu.");
        cfg.set("products." + id, null);
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload(); return ActionResult.ok("Usunieto produkt " + id + ".");
    }
    private ActionResult productSet(String cat, String id, String key, Object value) {
        File f = catFile(sanitize(cat));
        if (!f.exists()) return ActionResult.error("Tylko produkty w manualnych kategoriach.");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        if (!cfg.contains("products." + id)) return ActionResult.error("Nie znaleziono produktu.");
        cfg.set("products." + id + "." + key, value);
        if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
        plugin.reload(); return ActionResult.ok("Produkt " + id + " zaktualizowany.");
    }

    /**
     * Wlacza/wylacza kupno produktu.
     * Manual: categories/&lt;id&gt;.yml products.*.enabled
     * Provider (BellItems itd.): providers/&lt;src&gt;.yml item-enabled.&lt;key&gt;
     */
    private ActionResult productSetEnabled(String cat, String id, boolean value) {
        File f = catFile(sanitize(cat));
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            if (cfg.contains("products." + id)) {
                cfg.set("products." + id + ".enabled", value);
                if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
                plugin.reload();
                return ActionResult.ok("Produkt " + id + (value ? " wlaczony do kupna." : " wylaczony z kupna."));
            }
        }
        Product p = findProduct(id);
        if (p == null) return ActionResult.error("Nie znaleziono produktu.");
        String source = srcOf(p);
        String[] map = providerPriceTarget(source);
        if (map == null) return ActionResult.error("Wylaczanie kupna nieobslugiwane dla zrodla '" + source + "'.");
        File pf = new File(plugin.getDataFolder(), "providers/" + map[0]);
        YamlConfiguration cfg = pf.exists() ? YamlConfiguration.loadConfiguration(pf) : new YamlConfiguration();
        String key = id.startsWith(map[2]) ? id.substring(map[2].length()) : id;
        cfg.set("item-enabled." + key, value);
        // Jesli bylo w excluded-items (stary „Ukryj”), przy wlaczeniu kupna wyciagnij stamtad
        if (value) {
            List<String> excluded = new ArrayList<>(cfg.getStringList("excluded-items"));
            if (excluded.remove(key)) cfg.set("excluded-items", excluded);
        }
        if (!save(cfg, pf)) return ActionResult.error("Blad zapisu override.");
        plugin.reload();
        return ActionResult.ok("Produkt " + stripColor(p.getName())
                + (value ? " wlaczony do kupna." : " wylaczony z kupna.") + " (" + source + ")");
    }

    /**
     * Hurtowa zmiana cen w sekcji.
     * mode: set = wszyscy na value; multiply = cena * (value/100) gdy value jak procent (np. 110 = +10%),
     *       albo add = cena + value.
     */
    private ActionResult categorySetAllPrices(String catId, String mode, String valueRaw) {
        if (catId == null || catId.isBlank()) return ActionResult.error("Brak kategorii.");
        Category cat = findCategory(catId);
        if (cat == null || cat.getProducts().isEmpty()) return ActionResult.error("Pusta / nieznana kategoria.");
        String m = mode == null ? "set" : mode.trim().toLowerCase();
        long value = parseLong(valueRaw);
        if ("set".equals(m) && value < 0) return ActionResult.error("Cena nie moze byc ujemna.");
        int n = 0;
        for (Product p : cat.getProducts()) {
            long next = switch (m) {
                case "multiply", "mul", "percent", "pct" -> Math.max(0L, Math.round(p.getPrice() * (value / 100.0)));
                case "add" -> Math.max(0L, p.getPrice() + value);
                default -> value; // set
            };
            ActionResult r = productSetPrice(catId, p.getId(), next, false);
            if (!r.ok()) return r;
            n++;
        }
        plugin.reload();
        return ActionResult.ok("Zaktualizowano ceny " + n + " produktow w „" + catId + "” (" + m + ").");
    }

    /** Zapis wielu cen naraz. Format changes: id:cena|id:cena|... */
    private ActionResult productSetPrices(String cat, String changesRaw) {
        if (changesRaw == null || changesRaw.isBlank()) return ActionResult.error("Brak zmian do zapisu.");
        int n = 0;
        for (String part : changesRaw.split("\\|")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            if (colon <= 0) return ActionResult.error("Zly format changes (oczekiwano id:cena|...).");
            String id = s.substring(0, colon).trim();
            long price = parseLong(s.substring(colon + 1));
            ActionResult r = productSetPrice(cat, id, price, false);
            if (!r.ok()) return r;
            n++;
        }
        if (n == 0) return ActionResult.error("Brak zmian do zapisu.");
        plugin.reload();
        return ActionResult.ok("Zapisano " + n + " cen.");
    }

    /** Cena uniwersalna: manualny produkt -> YAML kategorii; produkt providera -> override providers/&lt;src&gt;.yml. */
    private ActionResult productSetPrice(String cat, String id, long price, boolean reload) {
        if (price < 0) return ActionResult.error("Cena nie moze byc ujemna.");
        // manualny?
        File f = catFile(sanitize(cat));
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            if (cfg.contains("products." + id)) {
                cfg.set("products." + id + ".price", price);
                if (!save(cfg, f)) return ActionResult.error("Blad zapisu.");
                if (reload) plugin.reload();
                return ActionResult.ok("Cena " + id + " = " + price + ".");
            }
        }
        // produkt providera -> override w providers/<source>.yml
        Product p = findProduct(id);
        if (p == null) return ActionResult.error("Nie znaleziono produktu.");
        String source = srcOf(p);
        String[] map = providerPriceTarget(source); // [plik, sekcja, prefiks-do-usuniecia]
        if (map == null) return ActionResult.error("Edycja ceny zrodla '" + source + "' nieobslugiwana w panelu.");
        File pf = new File(plugin.getDataFolder(), "providers/" + map[0]);
        YamlConfiguration cfg = pf.exists() ? YamlConfiguration.loadConfiguration(pf) : new YamlConfiguration();
        String key = id.startsWith(map[2]) ? id.substring(map[2].length()) : id;
        cfg.set(map[1] + "." + key, price);
        if (!save(cfg, pf)) return ActionResult.error("Blad zapisu override.");
        if (reload) plugin.reload();
        return ActionResult.ok("Cena " + stripColor(p.getName()) + " = " + price + " (override " + source + ").");
    }

    /** Wylacza produkt providera (dodaje do excluded-items) albo wlacza z powrotem. */
    private ActionResult productExclude(String cat, String id, boolean exclude) {
        Product p = findProduct(id);
        if (p == null) return ActionResult.error("Nie znaleziono produktu.");
        String source = srcOf(p);
        if ("manual".equals(source)) {
            return productSet(cat, id, "enabled", !exclude);
        }
        String[] map = providerPriceTarget(source);
        if (map == null) return ActionResult.error("Exclude nieobslugiwane dla zrodla '" + source + "'.");
        File pf = new File(plugin.getDataFolder(), "providers/" + map[0]);
        YamlConfiguration cfg = pf.exists() ? YamlConfiguration.loadConfiguration(pf) : new YamlConfiguration();
        String key = id.startsWith(map[2]) ? id.substring(map[2].length()) : id;
        List<String> excluded = new ArrayList<>(cfg.getStringList("excluded-items"));
        if (exclude) {
            if (!excluded.contains(key)) excluded.add(key);
        } else {
            excluded.remove(key);
        }
        cfg.set("excluded-items", excluded);
        if (!save(cfg, pf)) return ActionResult.error("Blad zapisu.");
        plugin.reload();
        return ActionResult.ok(exclude ? ("Ukryto " + key + " w sklepie.") : ("Przywrocono " + key + " do sklepu."));
    }

    private String[] providerPriceTarget(String source) {
        return switch (source) {
            case "skinstudio" -> new String[]{"skinstudio.yml", "skin-prices", "skinstudio_"};
            case "elitemobs" -> new String[]{"elitemobs.yml", "item-prices", "elitemobs_"};
            case "fmm" -> new String[]{"fmm.yml", "model-prices", "fmm_"};
            case "bellitems" -> new String[]{"bellitems.yml", "item-prices", "bellitems_"};
            default -> null;
        };
    }

    // ── helpers ──
    private Category findCategory(String id) {
        if (id == null) return null;
        for (Category c : plugin.getCategories().getCategories())
            if (c.getId().equals(id)) return c;
        return null;
    }
    private Product findProduct(String id) {
        for (Category c : plugin.getCategories().getCategories())
            for (Product p : c.getProducts())
                if (p.getId().equals(id)) return p;
        return null;
    }
    private String srcOf(Product p) {
        String s = p.getProviderSource();
        return s != null ? s : "manual";
    }
    private boolean save(YamlConfiguration cfg, File f) {
        try { f.getParentFile().mkdirs(); cfg.save(f); return true; }
        catch (Exception e) { plugin.getLogger().warning("BellHub zapis: " + e.getMessage()); return false; }
    }
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[^a-z0-9_-]", "");
    }
    private static long parseLong(String s) { return (s == null || s.isBlank()) ? 0L : Long.parseLong(s.trim()); }
    private UUID resolve(String name) {
        if (name == null || name.isBlank()) return null;
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }
    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").replaceAll("§x(§[0-9a-fA-F]){6}", "");
    }
    private static String arr(List<String> items) { return "[" + String.join(",", items) + "]"; }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
