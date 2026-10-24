package net.unit8.http.router;

import net.unit8.http.router.segment.DividerSegment;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1本のルート。
 * <p>
 * 認識に使う正規表現、各セグメントのキャプチャ数、controller / action の要件はコンストラクタで確定する。
 * 以降は読み取り専用なので、ロックなしで複数スレッドから使える。
 */
public class Route {
	private final List<Segment> segments;
	private final Options requirements;
	private final Options conditions;
	private final List<String> significantKeys;
	private final Options parameterShell;
	private final List<Object> allowedMethods;
	private final String controllerRequirement;
	private final String actionRequirement;
	private final Pattern recognizePattern;
	private final int[] captureCounts;

	public Route(List<Segment> segments, Options requirements, Options conditions) {
		this.segments = Collections.unmodifiableList(new ArrayList<Segment>(segments));
		this.requirements = requirements;
		this.conditions = conditions;

		Set<String> sk = new HashSet<String>();
		for (Segment segment : this.segments) {
			if (segment.hasKey()) {
				sk.add(segment.getKey());
			}
		}
		sk.addAll(requirements.keySet());
		if (!sk.contains("action") && !requirements.containsKey("action")) {
			requirements.$("action", "index");
			sk.add("action");
		}
		this.significantKeys = Collections.unmodifiableList(new ArrayList<String>(sk));

		Options shell = new Options();
		for (Map.Entry<String, Object> e : requirements.entrySet()) {
			if (!(e.getValue() instanceof Pattern)) {
				shell.put(e.getKey(), e.getValue());
			}
		}
		this.parameterShell = shell;

		this.allowedMethods = Collections.unmodifiableList(new ArrayList<Object>(conditions.getList("method")));
		this.controllerRequirement = requirementFor("controller");
		this.actionRequirement = requirementFor("action");

		this.recognizePattern = Pattern.compile(recognitionPattern(this.segments));
		this.captureCounts = new int[this.segments.size()];
		for (int i = 0; i < captureCounts.length; i++) {
			captureCounts[i] = this.segments.get(i).numberOfCaptures();
		}
	}

	public List<Segment> getSegments() {
		return segments;
	}

	public String buildQueryString(Map<String, String> hash) {
		List<String> elements = new ArrayList<String>();
		for(String key : hash.keySet()) {
			String value = hash.get(key);
			if (!ARStringUtil.isEmpty(value)) {
				elements.add(key + "=" + value);
			}
		}
		return ARStringUtil.join(elements, "&");
	}

	public List<String> significantKeys() {
		return significantKeys;
	}

	@Override
	public String toString() {
		StringBuilder segs = new StringBuilder();
		for (Segment s : segments) {
			segs.append(s.toString());
		}
		List<Object> methods = new ArrayList<Object>(allowedMethods);
		if (methods.isEmpty()) {
			methods.add("any");
		}
		StringBuilder out = new StringBuilder(256);
		for (Object method : methods) {
			out.append(String.format("%-6s %-40s %s\n", method.toString().toUpperCase(), segs.toString(), requirements));
		}
		return out.toString();
	}

	/*----recognize----*/

	/**
	 * @return 一致したときのパラメータ。一致しない場合、またはパスに不正なパーセントエンコーディングが含まれる場合は null
	 */
	public Options recognize(String path, String method) {
		if (!allowedMethods.isEmpty() && !allowedMethods.contains(method)) {
			return null;
		}
		Matcher match = recognizePattern.matcher(path);
		if (!match.find()) {
			return null;
		}
		Options params = new Options(parameterShell);
		try {
			int nextCapture = 1;
			for (int i = 0; i < captureCounts.length; i++) {
				segments.get(i).matchExtraction(params, match, nextCapture);
				nextCapture += captureCounts[i];
			}
		} catch (IllegalArgumentException e) {
			// 不正なパーセントエンコーディング(例: /users/%zz)。例外にせず「一致なし」として扱う
			return null;
		}
		return params;
	}

	private static String recognitionPattern(List<Segment> segments) {
		String pattern = "";
		for (int i = segments.size() - 1; i >= 0; i--) {
			Segment segment = segments.get(i);
			pattern = segment.buildPattern(pattern);
		}
		return "\\A" + pattern + "\\Z";
	}

	private String buildQueryString(Options hash, List<String> onlyKeys) {
		List<String> elements = new ArrayList<String>(hash.size());

		if (onlyKeys == null)
			onlyKeys = new ArrayList<String>(hash.keySet());

		for (String key : onlyKeys) {
			if (hash.containsKey(key)) {
				elements.add(hash.getUrlEncodedString(key));
			}
		}
		return elements.isEmpty() ? "" : "?" + ARStringUtil.join(elements, "&");
	}

	/* --- generate --- */
	public boolean matchesControllerAndAction(String controller, String action) {
		return  (controllerRequirement == null || ARStringUtil.equals(controllerRequirement, controller)) &&
				(actionRequirement == null || ARStringUtil.equals(actionRequirement, action));
	}

	public String generate(Options options, Options hash) {
		if (generationRequirements(options, hash)) {
			int lastIndex = segments.size() - 1;
			Segment last = segments.get(lastIndex);
			String path = last.stringStructure(segments.subList(0, lastIndex), hash);
			if (segments.size() > 1 && last instanceof DividerSegment && ARStringUtil.equals(last.getValue(), "/")) {
				path = path + "/";
			}
			return appendQueryString(path, hash, extraKeys(options));
		}
		return null;
	}

	public boolean generationRequirements(Options options, Options hash) {
		boolean matched = true;
		for(String key : requirements.keySet()) {
			Object req = requirements.get(key);
			if (req instanceof Pattern) {
				matched &= (hash.containsKey(key) && ((Pattern)req).matcher(options.getString(key)).matches());
			} else {
				matched &= ARStringUtil.equals(hash.getString(key), requirements.getString(key));
			}
		}
		return matched;
	}
	private String requirementFor(String key) {
		if (requirements.containsKey(key))
			return requirements.getString(key);
		for (Segment segment : segments) {
			if (segment.hasKey() && ARStringUtil.equals(segment.getKey(), key)) {
				return segment.getRegexp();
			}
		}
		return null;
	}

	private String appendQueryString(String path, Options hash, List<String> queryKeys) {
		if (path == null)
			return null;

		if (queryKeys == null)
			queryKeys = extraKeys(hash);

		return path + buildQueryString(hash, queryKeys);
	}

	private List<String> extraKeys(Options hash) {
		List<String> extraKeys = new ArrayList<String>();
		if (hash != null) {
			for (String key : hash.keySet()) {
				if (!significantKeys.contains(key))
					extraKeys.add(key);
			}
		}
		return extraKeys;
	}
}
