package net.unit8.http.router;

/**
 * パスからルートを探す。
 * <p>
 * 実装は不変であること。ルートの一覧はコンストラクタで受け取り、以降は変更しない。
 * ルートを入れ替えるときは、新しいインスタンスを作って参照を差し替える。
 */
public abstract class Recognizer {
	/**
	 * @return 一致したルートのパラメータ。一致するルートがなければ null
	 */
	public abstract Options recognize(String path, String method);
}
