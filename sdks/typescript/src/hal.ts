/**
 * The HAL envelope the server currently speaks.
 *
 * Not re-exported from `index.ts`, which is the package's only entry point, so
 * this is unreachable from outside. HAL is a transport detail: the SDK reads
 * `_links` to discover where things live and hands callers plain objects, so
 * nothing in the public surface should mention it. The server is moving to
 * HAL-less endpoints, and when it does this module goes away without a version
 * bump -- which it could not have done while `ApiRoot` was exported.
 *
 * Java keeps the same type in `fm.internal`, and Python in `fm/_hal.py`.
 */

export interface ApiRoot {
  links: Record<string, string>;
}
