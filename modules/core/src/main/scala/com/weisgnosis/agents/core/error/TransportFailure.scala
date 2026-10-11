/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.error

import java.net.{ConnectException, SocketTimeoutException, UnknownHostException}
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** External exception messages, class names and endpoint details are not transport observations. */
private[agents] object TransportFailure:
  def describe(context: String, error: Throwable): String =
    @scala.annotation.tailrec
    def category(current: Throwable, remaining: Int): String = current match
      case _: UnknownHostException | _: UnresolvedAddressException                   => "dns"
      case _: SSLException                                                           => "tls"
      case _: ConnectException                                                       => "connection"
      case _: SocketTimeoutException | _: HttpTimeoutException | _: TimeoutException => "timeout"
      case _ if remaining > 0 =>
        Option(current.getCause) match
          case Some(cause) => category(cause, remaining - 1)
          case None        => "other"
      case _ => "other"
    s"$context (${category(error, 7)})"
