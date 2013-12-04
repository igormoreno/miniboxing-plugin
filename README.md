<img src="http://scala-miniboxing.org/mbox2-thumbnail.png" alt="Miniboxing Logo" align="right">
#Miniboxing Plugin for Scala#

Miniboxing is a lightweight approach to specializing generic classes in the Scala compiler. It revisits the current [specialization](http://infoscience.epfl.ch/record/150134/files/p42-dragos.pdf) transformation, but makes different choices aimed at reducing the bytecode without sacrificing execution speed.

#### Check out [the scala-miniboxing.org website](http://scala-miniboxing.org).

##Why use Miniboxing?##
**Short answer:** because it matches the performance of specialization, without the bytecode blowup. For the `Tuple3` class:
```
case class Tuple3[@specialized +T1, @specialized +T2, @specialized +T3](_1: T1, _2: T2, _3: T3)
```
Specialization generates **1000 classes**. Just change `@specialized` to `@miniboxed` and you get **only 8 classes**.

**Long answer:** Aside from reducing the bytecode size, the miniboxing technique improves several other aspects of specialization:
 * miniboxing-specialized classes don't inherit generic fields (see [SI-3585](https://issues.scala-lang.org/browse/SI-3585));
 * miniboxing-specialized classes can inherit from their miniboxing-specialized parents (see [this restriction](https://github.com/scala/scala/blob/master/src/compiler/scala/tools/nsc/transform/SpecializeTypes.scala#L572)).

To see the benchmarks we performed, have a look at the [OOPSLA 2013 paper](https://github.com/miniboxing/miniboxing-plugin/blob/wip/docs/2013-07-oopsla-preprint.pdf) we just prepared. They include performance evaluations, bytecode size comparisons and many more. Also, the [docs](https://github.com/miniboxing/miniboxing-plugin/tree/wip/docs) directory contains a series of papers and presentations which explain many aspects of the miniboxing transformation.

## Using the Plugin ##
At this point, the miniboxing plugin is not production-ready, although it can compile [spire](https://github.com/non/spire). However, we do publish a [nightly maven artifact](https://scala-webapps.epfl.ch/jenkins/view/All/job/miniboxing-wip-nightly/) on Sonatype, so anyone can try the current transformation. To get started, have a look at the [example project](https://github.com/miniboxing/miniboxing-example) we prepared. 

**Mind the gap:** there are still many [bugs](https://github.com/miniboxing/miniboxing-plugin/issues?state=open) and [known limitations](https://github.com/miniboxing/miniboxing-plugin/wiki/Details-|-Known-Limitations), so you're in for a thrill! Also, don't hesitate to add bugs to the tracker, good reductions that can be easily reproduced are highly appreciated!

## Hacking the Plugin ##
The [wiki](https://github.com/miniboxing/miniboxing-plugin/wiki) is a good place to start looking into installing, testing, benchmarking and hacking on the miniboxing plugin. Also have a look at the [docs](https://github.com/miniboxing/miniboxing-plugin/tree/wip/docs) directory, which contains some good resources.

The development branches are:
 - [master](https://github.com/miniboxing/miniboxing-plugin/tree/master) is always stable, usually outdated
 - [wip](https://github.com/miniboxing/miniboxing-plugin/tree/wip) is pretty stable, usually has the last resonably stable developments
 - [topic/erasure-rebase](https://github.com/miniboxing/miniboxing-plugin/tree/topic/erasure-rebase) contains the most recent developments, but expect tests to be broken most of the time

###Repository organization###
 - wip is the working branch, most current version of the plugin
 - master is usually a bit behind wip, but should be stable (alpha-stable, not production-ready!)
 - sbt is used for compilation and testing
 - the repository contains several sbt sub-projects:
   - `components/plugin`      - the actual Scala compiler plugin
   - `components/runtime`     - the runtime support for the transformed code
   - `components/classloader` - the classloader used for runtime class specialization
   - `tests/benchmarks`       - the benchmarks for the project
   - `tests/correctness`      - the tests for the plugin transformation
   - `docs`                   - documents released as the development goes on

##Questions?##
If you have any question, you can contact me at vlad dot ureche at epfl dot ch.
