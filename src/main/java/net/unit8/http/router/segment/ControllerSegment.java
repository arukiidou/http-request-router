package net.unit8.http.router.segment;

import net.unit8.http.router.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

public class ControllerSegment extends DynamicSegment {
	/** null のときは {@link Routes#possibleControllers()} を使う */
	private final List<String> possibleControllers;

	public ControllerSegment(String value, Options options) {
		this(value, options, null);
	}

	public ControllerSegment(String value, Options options, List<String> possibleControllers) {
		super(value, options);
		this.possibleControllers = possibleControllers;
	}

	public ControllerSegment(String key) {
		this(key, new Options(), null);
	}

	@Override
	public String regexpChunk() {
		List<String> possibleNames = new ArrayList<String>();
		List<String> controllers = possibleControllers != null ? possibleControllers : Routes.possibleControllers();
		for (String name : controllers) {
			possibleNames.add(RegexpUtil.escape(name));
		}
		return "(?i-:(" + ARStringUtil.join(possibleNames, "|")+ "))";
	}

	@Override
	public void matchExtraction(Options params, Matcher match, int nextCapture) {
		String key = getKey();
		String token = match.group(nextCapture);
		if (getDefault() != null) {
			params.put(key, !ARStringUtil.isEmpty(token) ? ControllerUtil.fromPathToClassName(token) : getDefault());
		} else {
			 if (!ARStringUtil.isEmpty(token))
				 params.put(key, ControllerUtil.fromPathToClassName(token));
		}
	}

	@Override
	public String interpolationChunk(Options hash) {
		String value = hash.getString(getKey());
		String path = value.replace(".", "/");
		if (path != null) {
			if (path.lastIndexOf('/') >= 0)
				path = ARStringUtil.substringBeforeLast(path, "/") + "/" + ARStringUtil.decapitalize(ARStringUtil.substringAfterLast(path, "/"));
			else
				path = ARStringUtil.decapitalize(path);
		}
		return path;
	}
}
