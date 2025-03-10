package com.twitter.finagle.loadbalancer

import com.twitter.finagle._
import com.twitter.finagle.util.Ema
import com.twitter.util.Duration
import com.twitter.util.Future
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.util.Time

/**
 * Provides a Node that is hyper-sensitive to latent endpoints.
 *
 * Peak EWMA is designed to converge quickly when encountering slow endpoints. It
 * is quick to react to latency spikes, recovering only cautiously. Peak EWMA takes
 * history into account, so that slow behavior is penalized relative to the
 * supplied `decayTime`.
 */
private trait PeakEwma[Req, Rep] extends BalancerNode[Req, Rep] { self: Balancer[Req, Rep] =>

  protected type Node <: PeakEwmaNode

  /**
   * The moving window over which latency is observed.
   */
  protected def decayTime: Duration

  /**
   * Returns the current time in nanos.
   */
  protected val nanoTime: () => Long

  private class Metric {
    private[this] val Penalty: Double = Long.MaxValue >> 16
    // these are all guarded by synchronization on `this`
    private[this] var stamp: Long = nanoTime() // last timestamp in nanos we observed an rtt
    private[this] var pending: Int = 0 // instantaneous rate
    // ewma of rtt, sensitive to peaks
    private[this] val cost: Ema = {
      // The mean lifetime of `cost`, it reaches its half-life after Tau*ln(2).
      val tau = decayTime.inNanoseconds
      require(tau > 0)

      new Ema(tau)
    }

    def rate(): Int = synchronized { pending }

    // Calculate the exponential weighted moving average of our
    // round trip time. It isn't exactly an ewma, but rather a
    // "peak-ewma", since `cost` is hyper-sensitive to latency peaks.
    // Note, because the frequency of observations represents an
    // unevenly spaced time-series[1], we consider the time between
    // observations when calculating our weight.
    // [1] http://www.eckner.com/papers/Algorithms%20for%20Unevenly%20Spaced%20Time%20Series.pdf
    private[this] def observe(rtt: Double): Unit = {
      val t = nanoTime()
      // Enforce monotonicity
      if (t - stamp > 0) {
        stamp = t
      }

      // Be sensitive to peaks
      if (rtt > cost.last) {
        cost.reset()
      }

      cost.update(stamp, rtt)
    }

    def get(): Double = synchronized {
      // update our view of the decay on `cost`
      observe(0.0)

      // If we don't have any latency history, we penalize the host on
      // the first probe. Otherwise, we factor in our current rate
      // assuming we were to schedule an additional request.
      val lcost = cost.last
      if (lcost == 0.0 && pending != 0) Penalty + pending
      else lcost * (pending + 1)
    }

    def start(): Long = synchronized {
      pending += 1
      nanoTime()
    }

    def end(ts: Long): Unit = synchronized {
      val rtt = math.max(nanoTime() - ts, 0)
      pending -= 1
      observe(rtt)
    }
  }

  protected trait PeakEwmaNode extends NodeT[Req, Rep] {
    private[this] val metric: Metric = new Metric

    def load: Double = metric.get()
    def pending: Int = metric.rate()

    abstract override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
      val ts = metric.start()
      super.apply(conn).transform {
        case Return(svc) =>
          Future.value(new ServiceProxy(svc) {
            override def close(deadline: Time): Future[Unit] =
              super.close(deadline).respond { _ => metric.end(ts) }
          })

        case t @ Throw(_) =>
          metric.end(ts)
          Future.const(t)
      }
    }
  }
}
