package net.unit8.http.router.recognizer;

import net.unit8.http.router.Options;
import net.unit8.http.router.Recognizer;
import net.unit8.http.router.Route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleRecognizer extends Recognizer {
	private final List<Route> routes;

	public SimpleRecognizer(List<Route> routes) {
		this.routes = Collections.unmodifiableList(new ArrayList<Route>(routes));
	}

	@Override
	public Options recognize(String path, String method) {
		for (Route route : routes) {
			Options result = route.recognize(path, method);
			if (result != null) return result;
		}
		return null;
	}
}
