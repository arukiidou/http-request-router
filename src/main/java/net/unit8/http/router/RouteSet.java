package net.unit8.http.router;

import java.io.File;
import java.io.InputStream;
import java.util.*;

/**
 * ルーティング表を保持する。
 * <p>
 * リクエスト処理(認識・URL生成)はロックを取らず、volatile な {@code table} を1回読むだけ。
 * 設定の読み込みは新しい表を別に組み立ててから参照を差し替えるので、読み込み中も旧い表で処理が続く。
 * 書き込み系のメソッド(load / loadStream / addRoute / clear)だけが互いに排他される。
 */
public class RouteSet {
	private final TreeSet<File> configurationFiles = new TreeSet<File>();
	private volatile RoutingTable table = RoutingTable.EMPTY;
	/** 直近の読み込み時点のコントローラ名。guarded by this */
	private List<String> controllers;

	public RouteSet() {
	}

	public synchronized void clear() {
		table = RoutingTable.EMPTY;
	}

	public synchronized void addConfigurationFile(File file) {
		configurationFiles.add(file);
	}

	public Set<File> getConfigurationFiles() {
		return configurationFiles;
	}

	public synchronized void load() {
		RouteBuilder builder = newBuilder();
		List<Route> routes;
		if (!configurationFiles.isEmpty()) {
			routes = Collections.emptyList();
			for (File config : configurationFiles) {
				routes = new RouteLoader(builder).load(config);
			}
		} else {
			routes = new ArrayList<Route>();
			routes.add(builder.build(":controller/:action/:id", new Options()));
		}
		table = new RoutingTable(routes);
	}

	public synchronized void loadStream(InputStream stream) {
		List<Route> routes = new RouteLoader(newBuilder()).load(stream);
		table = new RoutingTable(routes);
	}

	/**
	 * コントローラ名の検出(時間がかかることがある)は、リクエストの経路ではなくここで行う。
	 */
	private RouteBuilder newBuilder() {
		controllers = Routes.possibleControllers();
		return new RouteBuilder(controllers);
	}

	public synchronized Route addRoute(String path, Options options) {
		Route route = new RouteBuilder(controllers).build(path, options);
		List<Route> next = new ArrayList<Route>(table.routes());
		next.add(route);
		table = new RoutingTable(next);
		return route;
	}

	/**
	 * @throws RoutingException 一致するルートがない場合
	 */
	public Options recognizePath(String path, String method) {
		Options result = table.recognize(path, method);
		if (result == null) {
			throw new RoutingException("No route matches " + path);
		}
		return result;
	}

	/**
	 * 一致するルートがない場合に例外を投げず null を返す。
	 * 「一致なし」を通常の結果として扱いたい呼び出し側向け。
	 * (測定上、一致しないパスのコストは例外の生成よりも全ルートの走査が支配的で、速度は変わらない)
	 */
	public Options tryRecognizePath(String path, String method) {
		return table.recognize(path, method);
	}

	public String generate(Options options) {
		return table.generate(options);
	}

	public String toString() {
		StringBuilder out = new StringBuilder(1024);
		for (Route route : table.routes()) {
			out.append(route.toString());
		}
		return out.toString();
	}
}
