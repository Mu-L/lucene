/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.queries.intervals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.lucene.util.PriorityQueue;

class UnorderedIntervalsSource extends MinimizingConjunctionIntervalsSource {

  static IntervalsSource build(List<IntervalsSource> sources) {
    if (sources.size() == 1) {
      return sources.get(0);
    }
    List<IntervalsSource> rewritten = deduplicate(sources);
    if (rewritten.size() == 1) {
      return rewritten.get(0);
    }
    return new UnorderedIntervalsSource(rewritten);
  }

  private static List<IntervalsSource> deduplicate(List<IntervalsSource> sources) {
    Map<IntervalsSource, Integer> counts = new LinkedHashMap<>(); // preserve order for testing
    for (IntervalsSource source : sources) {
      counts.compute(source, (_, v) -> v == null ? 1 : v + 1);
    }
    List<IntervalsSource> deduplicated = new ArrayList<>();
    for (IntervalsSource source : counts.keySet()) {
      deduplicated.add(RepeatingIntervalsSource.build(source, counts.get(source)));
    }
    if (deduplicated.size() == 1 && deduplicated.get(0) instanceof RepeatingIntervalsSource) {
      ((RepeatingIntervalsSource) deduplicated.get(0)).setName("UNORDERED");
    }
    return deduplicated;
  }

  private UnorderedIntervalsSource(List<IntervalsSource> sources) {
    super(sources);
  }

  @Override
  protected IntervalIterator combine(List<IntervalIterator> iterators, MatchCallback onMatch) {
    return new UnorderedIntervalIterator(iterators, onMatch);
  }

  @Override
  public int minExtent() {
    int minExtent = 0;
    for (IntervalsSource subSource : subSources) {
      minExtent += subSource.minExtent();
    }
    return minExtent;
  }

  @Override
  public Collection<IntervalsSource> pullUpDisjunctions() {
    return Disjunctions.pullUp(subSources, UnorderedIntervalsSource::new);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.subSources);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof UnorderedIntervalsSource == false) return false;
    UnorderedIntervalsSource o = (UnorderedIntervalsSource) other;
    return Objects.equals(this.subSources, o.subSources);
  }

  @Override
  public String toString() {
    return "UNORDERED("
        + subSources.stream().map(IntervalsSource::toString).collect(Collectors.joining(","))
        + ")";
  }

  private static class UnorderedIntervalIterator extends ConjunctionIntervalIterator {

    private final PriorityQueue<IntervalIterator> queue;
    private final IntervalIterator[] subIterators;
    private final MatchCallback onMatch;

    int start = -1, end = -1, slop, queueEnd;

    UnorderedIntervalIterator(List<IntervalIterator> subIterators, MatchCallback onMatch) {
      super(subIterators);
      this.queue =
          PriorityQueue.usingComparator(
              subIterators.size(),
              Comparator.comparingInt(IntervalIterator::start)
                  .thenComparing(Comparator.comparingInt(IntervalIterator::end).reversed()));
      this.subIterators = new IntervalIterator[subIterators.size()];
      this.onMatch = onMatch;

      for (int i = 0; i < subIterators.size(); i++) {
        this.subIterators[i] = subIterators.get(i);
      }
    }

    @Override
    public int start() {
      return start;
    }

    @Override
    public int end() {
      return end;
    }

    void updateRightExtreme(IntervalIterator it) {
      int itEnd = it.end();
      if (itEnd > queueEnd) {
        queueEnd = itEnd;
      }
    }

    @Override
    public int nextInterval() throws IOException {
      // first, find a matching interval
      while (this.queue.size() == subIterators.length && queue.top().start() == start) {
        IntervalIterator it = queue.pop();
        if (it != null && it.nextInterval() != IntervalIterator.NO_MORE_INTERVALS) {
          queue.add(it);
          updateRightExtreme(it);
        }
      }
      if (this.queue.size() < subIterators.length)
        return start = end = IntervalIterator.NO_MORE_INTERVALS;
      // then, minimize it
      do {
        start = queue.top().start();
        end = queueEnd;
        slop = width();
        for (IntervalIterator it : subIterators) {
          slop -= it.width();
        }
        onMatch.onMatch();
        if (queue.top().end() == end) {
          return start;
        }
        IntervalIterator it = queue.pop();
        if (it != null && it.nextInterval() != IntervalIterator.NO_MORE_INTERVALS) {
          queue.add(it);
          updateRightExtreme(it);
        }
      } while (this.queue.size() == subIterators.length && end == queueEnd);
      return start;
    }

    @Override
    public int gaps() {
      return slop;
    }

    @Override
    protected void reset() throws IOException {
      queueEnd = start = end = -1;
      this.queue.clear();
      for (IntervalIterator it : subIterators) {
        if (it.nextInterval() == NO_MORE_INTERVALS) {
          break;
        }
        queue.add(it);
        updateRightExtreme(it);
      }
    }
  }
}
