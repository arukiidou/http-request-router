package net.unit8.http.router.recognizer;

import net.unit8.http.router.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * パスの先頭から辿る木で、走査を始めるルートの位置を絞り込む。
 * <p>
 * 木はコンストラクタの中で完成させる。構築後は読み取り専用なので、ロックなしで複数スレッドから使える。
 */
public class OptimizedRecognizer extends Recognizer {
	private static final Pattern LEADING_SLASHES = Pattern.compile("^/+");
	private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
	private static final Pattern PLAIN_SEGMENT_SPLITTER = Pattern.compile(
			"\\.[^/]+\\/+|" + "[" + RegexpUtil.escape(ARStringUtil.join(RouteBuilder.SEPARATORS)) + "]+|\\.[^/]+\\Z");

	private final List<Route> routes;
	private final SegmentNode tree;

	public OptimizedRecognizer(List<Route> routes) {
		this.routes = Collections.unmodifiableList(new ArrayList<Route>(routes));
		this.tree = buildTree(this.routes);
	}

	public static String[] toPlainSegments(String str) {
		str = LEADING_SLASHES.matcher(str).replaceAll("");
		str = TRAILING_SLASHES.matcher(str).replaceAll("");
		return PLAIN_SEGMENT_SPLITTER.split(str);
	}

	private static SegmentNode buildTree(List<Route> routes) {
		SegmentNode root = new SegmentNode(null, 0);

		int i = -1;
		for (Route route : routes) {
			i += 1;
			SegmentNode node = root;
			String[] segments = toPlainSegments(ARStringUtil.join(route.getSegments(), ""));
			for (String seg : segments) {
				if (!ARStringUtil.isEmpty(seg) && seg.charAt(0) == ':') {
					seg = ":dynamic";
				}
				if (node.isEmpty() || !ARStringUtil.equals(node.lastChild().getLabel(), seg))
					node.add(new SegmentNode(seg, i));
				node = node.lastChild();
			}
		}
		return root;
	}

	private int calcIndex(String[] segments, SegmentNode node, int level) {
		if (node.size() <= 1 || segments.length == level)
			return node.getIndex();
		String seg = segments[level];
		for (SegmentNode item : node.getChildNodes()) {
			if (ARStringUtil.equals(item.getLabel(), ":dynamic") || ARStringUtil.equals(item.getLabel(), seg)) {
				return calcIndex(segments, item, level + 1);
			}
		}
		return node.getIndex();
	}

	@Override
	public Options recognize(String path, String method) {
		String[] segments = toPlainSegments(path);

		int index = calcIndex(segments, tree, 0);
		while (index < routes.size()) {
			Options result = routes.get(index).recognize(path, method);
			if (result != null) return result;
			index += 1;
		}
		return null;
	}

	private static final class SegmentNode {
		private final int index;
		private final String label;
		private final List<SegmentNode> childNodes = new ArrayList<SegmentNode>();

		SegmentNode(String label, int index) {
			this.index = index;
			this.label = label;
		}

		void add(SegmentNode child) {
			childNodes.add(child);
		}

		boolean isEmpty() {
			return childNodes.isEmpty();
		}

		SegmentNode lastChild() {
			if (isEmpty())
				return null;
			return childNodes.get(childNodes.size() - 1);
		}

		String getLabel() {
			return label;
		}

		int getIndex() {
			return index;
		}

		List<SegmentNode> getChildNodes() {
			return childNodes;
		}

		int size() {
			return childNodes.size();
		}
	}
}
