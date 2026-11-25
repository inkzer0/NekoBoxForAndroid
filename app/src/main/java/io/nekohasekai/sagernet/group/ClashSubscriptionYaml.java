package io.nekohasekai.sagernet.group;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.List;
import java.util.Map;

final class ClashSubscriptionYaml {
    private ClashSubscriptionYaml() {}

    static Map<?, ?> load(String text) {
        // loadAs would force a Java type tag onto the root, which SafeConstructor rejects.
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (!(root instanceof Map<?, ?>)) {
            throw new YAMLException("Clash subscriptions must have a mapping at the root");
        }
        Map<?, ?> document = (Map<?, ?>) root;
        Object proxies = document.get("proxies");
        if (!(proxies instanceof List<?>)) {
            throw new YAMLException("Clash subscriptions must contain a proxies list");
        }
        for (Object proxy : (List<?>) proxies) {
            if (!(proxy instanceof Map<?, ?>)) {
                throw new YAMLException("Clash subscription proxies must be mappings");
            }
        }
        return document;
    }
}
