package org.rapidoid.http.handler;

/*
 * #%L
 * rapidoid-http-fast
 * %%
 * Copyright (C) 2014 - 2016 Nikolche Mihajlovski and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.rapidoid.annotation.Authors;
import org.rapidoid.annotation.Since;
import org.rapidoid.annotation.TransactionMode;
import org.rapidoid.ctx.With;
import org.rapidoid.http.*;
import org.rapidoid.jpa.JPA;
import org.rapidoid.lambda.Mapper;
import org.rapidoid.net.abstracts.Channel;
import org.rapidoid.security.Secure;
import org.rapidoid.u.U;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Future;

@Authors("Nikolche Mihajlovski")
@Since("4.3.0")
public abstract class AbstractAsyncHttpHandler extends AbstractHttpHandler {

	private static final String CTX_TAG_HANDLER = "handler";

	private final FastHttp http;

	public AbstractAsyncHttpHandler(FastHttp http, RouteOptions options) {
		super(options);
		this.http = http;
	}

	@Override
	public boolean needsParams() {
		return true;
	}

	@Override
	public HttpStatus handle(Channel ctx, boolean isKeepAlive, Req req, Object extra) {
		U.notNull(req, "HTTP request");

		String username = req.cookiepack(HttpUtils._USER, null);
		Set<String> roles = userRoles(username);

		TransactionMode txMode;
		try {
			txMode = before(req, username, roles);

		} catch (Throwable e) {
			HttpIO.errorAndDone(req, e, http.custom().errorHandler());
			return HttpStatus.DONE;
		}

		U.notNull(txMode, "transactionMode");

		try {
			ctx.async();
			execHandlerJob(ctx, isKeepAlive, req, extra, txMode, username, roles);

		} catch (Throwable e) {
			// if there was an error in the job scheduling:
			HttpIO.errorAndDone(req, e, http.custom().errorHandler());
			return HttpStatus.DONE;
		}

		return HttpStatus.ASYNC;
	}

	private Set<String> userRoles(String username) {
		if (username != null) {
			try {
				return http.custom().rolesProvider().getRolesForUser(username);
			} catch (Exception e) {
				throw U.rte(e);
			}
		} else {
			return Collections.emptySet();
		}
	}

	private TransactionMode before(final Req req, String username, Set<String> roles) {

		if (U.notEmpty(options.roles()) && !Secure.hasAnyRole(username, roles, options.roles())) {
			throw new SecurityException("The user doesn't have the required roles!");
		}

		req.response().view(options.view()).contentType(options.contentType()).mvc(options.mvc());

		TransactionMode txMode = U.or(options.transactionMode(), TransactionMode.NONE);

		if (txMode == TransactionMode.AUTO) {
			txMode = HttpUtils.isGetReq(req) ? TransactionMode.READ_ONLY : TransactionMode.READ_WRITE;
		}

		return txMode;
	}

	@SuppressWarnings("unchecked")
	protected Object postprocessResult(Req req, Object result) throws Exception {
		if (result instanceof Req || result instanceof Resp || result instanceof HttpStatus) {
			return result;

		} else if (result == null) {
			return null; // not found

		} else if ((result instanceof Future<?>) || (result instanceof org.rapidoid.concurrent.Future<?>)) {
			return req.async();

		} else {
			return result;
		}
	}

	private void execHandlerJob(final Channel channel, final boolean isKeepAlive, final Req req,
	                            final Object extra, final TransactionMode txMode, String username, Set<String> roles) {

		Runnable handleRequest = handlerWithWrappers(channel, isKeepAlive, req, extra);
		Runnable handleRequestMaybeInTx = txWrap(txMode, handleRequest);

		With.tag(CTX_TAG_HANDLER).exchange(req).username(username).roles(roles).run(handleRequestMaybeInTx);
	}

	private Runnable handlerWithWrappers(final Channel channel, final boolean isKeepAlive, final Req req, final Object extra) {
		return new Runnable() {

			@Override
			public void run() {
				Object result;
				try {

					if (!U.isEmpty(wrappers)) {
						result = wrap(channel, isKeepAlive, req, 0, extra);
					} else {
						result = handleReq(channel, isKeepAlive, req, extra);
					}

					result = postprocessResult(req, result);
				} catch (Throwable e) {
					result = e;
				}

				complete(channel, isKeepAlive, req, result);
			}
		};
	}

	private Runnable txWrap(final TransactionMode txMode, final Runnable handleRequest) {
		if (txMode != null && txMode != TransactionMode.NONE) {

			return new Runnable() {
				@Override
				public void run() {
					if (txMode == TransactionMode.READ_ONLY) {
						JPA.transactionReadOnly(handleRequest);
					} else {
						JPA.transaction(handleRequest);
					}
				}
			};

		} else {
			return handleRequest;
		}
	}

	private Object wrap(final Channel channel, final boolean isKeepAlive, final Req req, final int index, final Object extra)
			throws Exception {
		HttpWrapper wrapper = wrappers[index];

		HandlerInvocation invocation = new HandlerInvocation() {

			@Override
			public Object invoke() throws Exception {
				return invokeAndTransformResult(null);
			}

			@Override
			public Object invokeAndTransformResult(Mapper<Object, Object> transformation) throws Exception {
				try {
					int next = index + 1;

					Object val;
					if (next < wrappers.length) {
						val = wrap(channel, isKeepAlive, req, next, extra);
					} else {
						val = handleReq(channel, isKeepAlive, req, extra);
					}

					return transformation != null ? transformation.map(val) : val;

				} catch (Throwable e) {
					return e;
				}
			}
		};

		Object result = wrapper.wrap(req, invocation);

		return result;
	}

	protected abstract Object handleReq(Channel ctx, boolean isKeepAlive, Req req, Object extra) throws Exception;

	public void complete(Channel ctx, boolean isKeepAlive, Req req, Object result) {

		if (result == null) {
			http.notFound(ctx, isKeepAlive, this, req);
			return; // not found
		}

		if (result instanceof HttpStatus) {
			complete(ctx, isKeepAlive, req, U.rte("HttpStatus result is not supported!"));
			return;
		}

		if (result instanceof Throwable) {
			HttpIO.error(req, (Throwable) result, http.custom().errorHandler());
		} else {
			HttpUtils.resultToResponse(req, result);
		}

		// the Req object will do the rendering
		if (!req.isAsync()) {
			req.done();
		}
	}

}
