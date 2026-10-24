package net.unit8.http.router;

import net.unit8.http.router.recognizer.OptimizedRecognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不変のルーティング表(スナップショット)。
 * <p>
 * ルートの一覧と認識用の木は構築時に完成させ、以降は変更しない。
 * 設定を読み直すときは新しい表を別に作り、{@link RouteSet} が参照を1回だけ差し替える。
 * <p>
 * 例外は URL 生成用の遅延キャッシュだけで、これはスレッドセーフな {@link ConcurrentHashMap} に持つ。
 * キャッシュは表の中にあるので、表を差し替えると自動的に捨てられる。
 */
final class RoutingTable {
	static final RoutingTable EMPTY = new RoutingTable(Collections.<Route>emptyList());

	private final List<Route> routes;
	private final OptimizedRecognizer recognizer;
	private final ConcurrentHashMap<Key, List<Route>> byControllerAction = new ConcurrentHashMap<Key, List<Route>>();

	RoutingTable(List<Route> routes) {
		this.routes = Collections.unmodifiableList(new ArrayList<Route>(routes));
		this.recognizer = new OptimizedRecognizer(this.routes);
	}

	List<Route> routes() {
		return routes;
	}

	/**
	 * @return 一致したルートのパラメータ。なければ null
	 */
	Options recognize(String path, String method) {
		return recognizer.recognize(path, method);
	}

	String generate(Options options) {
		Options merged = new Options(options);
		String controller = merged.getString("controller");
		String action     = merged.getString("action");
		if (ARStringUtil.isEmpty(controller) || ARStringUtil.isEmpty(action)) {
			throw new RoutingException("Need controller and action!");
		}
		for (Route route : routesFor(controller, action)) {
			if (!hasAllKey(route, options))
				continue;
			String results = route.generate(options, merged);
			if (!ARStringUtil.isEmpty(results)) {
				return results;
			}
		}
		throw new RoutingException("No route matches " + options.toString());
	}

	private static boolean hasAllKey(Route route, Options options) {
		for (String key : route.significantKeys()) {
			if (!options.containsKey(key))
				return false;
		}
		return true;
	}

	private List<Route> routesFor(String controller, String action) {
		Key key = new Key(controller, action);
		List<Route> cached = byControllerAction.get(key);
		if (cached != null) {
			return cached;
		}
		List<Route> matched = new ArrayList<Route>();
		for (Route route : routes) {
			if (route.matchesControllerAndAction(controller, action)) {
				matched.add(route);
			}
		}
		List<Route> computed = Collections.unmodifiableList(matched);
		List<Route> previous = byControllerAction.putIfAbsent(key, computed);
		return previous != null ? previous : computed;
	}

	private static final class Key {
		private final String controller;
		private final String action;

		Key(String controller, String action) {
			this.controller = controller;
			this.action = action;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Key)) return false;
			Key other = (Key) o;
			return controller.equals(other.controller) && action.equals(other.action);
		}

		@Override
		public int hashCode() {
			return 31 * controller.hashCode() + action.hashCode();
		}
	}
}
