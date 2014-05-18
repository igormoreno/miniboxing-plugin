package miniboxing.plugin
package transform
package dupl

import scala.tools.nsc.transform.InfoTransform
import scala.reflect.internal.Flags._
import scala.collection.mutable.HashMap

trait MiniboxDuplInfoTransformation extends InfoTransform {
  self: MiniboxDuplComponent =>

  import global._
  import definitions._

  /** Type transformation. It is applied to all symbols, compiled or loaded.
   *  If it is a 'no-specialization' run, it is applied only to loaded symbols. */
  override def transformInfo(sym: Symbol, tpe: Type): Type =
    try {
      tpe.resultType match {
        case cinfo @ ClassInfoType(parents, decls, origin) if heuristics.specializableClass(tpe) =>
          specializeClass(origin, cinfo)
        case _ if heuristics.normalizableMethodInMethod(sym) =>
          normalizeMember(sym); tpe
        case _ =>
          tpe
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace(System.err)
        throw t
    }

  /** Expand methods with specialized type parameters */
  private def normalizeMember(member: Symbol): List[Symbol] = {

    if (!member.isMethod || !member.hasMiniboxedTypeParameters)
      return List()

    if (!heuristics.specializableMethodInClass(member.owner, member))
      return List()

    // mark the member as the base
    metadata.setNormalStem(member, member)

    val tparams = member.getMiniboxedTypeParameters
    tparams foreach (_ setFlag MINIBOXED)
    val pspecs = variants.allSpecializations(tparams)
    val normalizedOverloads = new HashMap[PartialSpec, Symbol]

    // step1: create the normalized members
    val newMembers = (for (pspec <- pspecs) yield {
      var newMbr = member
      if (!PartialSpec.isAllAnyRef(pspec)) {

        newMbr = member.cloneSymbol(member.owner)
        newMbr setFlag MINIBOXED
        newMbr setName (specializedName(newTermName(member.name.toString + "_n"), variants.typeParamValues(member, pspec)))
        newMbr modifyInfo (info0 => {
          info0.typeParams.foreach(_.removeAnnotation(MinispecClass))

          // update the signature to include @storage
          val deepEnv: Map[Symbol, Symbol] = // <old tparam> ==> <new tparam>
            member.typeParams.zip(info0.typeParams).toMap
          val normalizedSignatureEnv =       // <new tparam> ==> @storage <new tparam>
            pspec flatMap {
              case (p, Boxed)     => None // stays the same
              case (p, Miniboxed) => Some((deepEnv(p), storageType(deepEnv(p))))
            }
          val normalizedTypeMap = typeMappers.MiniboxSubst(normalizedSignatureEnv)
          val info1 = normalizedTypeMap(info0.resultType)

          // update local type tags (inherited from class) to the new parameters
          val updateParams = (member.info.params zip info1.params).toMap
          val oldLocalTypeTags = metadata.localTypeTags.getOrElse(member, Map())
          val updLocalTypeTags = oldLocalTypeTags.map({ case (tag, tpe) => (updateParams(tag), tpe)})
          metadata.localTypeTags(newMbr) = HashMap() ++ updLocalTypeTags

          // create type tags for the method's own miniboxed parameters
          val localTags =
            for (tparam <- member.typeParams if tparam.hasFlag(MINIBOXED) && pspec(tparam) == Miniboxed)
              yield (newMbr.newValue(shortTypeTagName(tparam), newMbr.pos).setInfo(ByteClass.tpe), deepEnv(tparam))
          metadata.normalTypeTags(newMbr) = HashMap() ++ localTags

          // update signature for created type tags
          val tagParams = localTags.map(_._1)
          GenPolyType(info0.typeParams, MethodType(tagParams ::: info1.params, info1.resultType))
        })
      }

      metadata.normalSpecialization(newMbr) = pspec
      normalizedOverloads(pspec) = newMbr
      metadata.setNormalStem(newMbr, member)

      (pspec, newMbr)
    })

    metadata.normalOverloads(member) = normalizedOverloads

    // step 2: set their specialization info
    for ((pspec, newMbr) <- newMembers if newMbr != member)
      memberSpecializationInfo(newMbr) =
        memberSpecializationInfo.get(member) match {
          case Some(Interface) =>
            Interface

          case Some(SpecializedImplementationOf(stemMethodInStemClass)) =>
            SpecializedImplementationOf(stemMethodInStemClass)

          case Some(ForwardTo(target)) =>
            ForwardTo(target)(overrider = false)

          case None =>
            SpecializedImplementationOf(member)

          case specialization =>
            global.error(
                s"""|Unknown info for ${member.defString}
                    |when specializing ${newMbr.defString}:
                    |specialization""".stripMargin)
            SpecializedImplementationOf(member)
        }

    // step 3: return the new members
    newMembers.map(_._2).filterNot(_ == member)
  }

  /** Expand classes with specialized type parameters */
  private def specializeClass(stemClass: Symbol, stemClassTpe: ClassInfoType): Type = {

    import specializationProcedures._

    // force info on parents to get all specialized metadata
    afterMiniboxDupl(stemClassTpe.parents.map(_.typeSymbol.info))
    val specs =
      if (heuristics.isSpecializableClass(stemClass))
        variants.allSpecializations(stemClass.info.typeParams)
      else
        Nil

    // set all type parameters to miniboxed
    // TODO: Do we use this anywhere?
    for (tpar <- stemClass.info.typeParams if tpar.isMiniboxAnnotated)
      tpar.setFlag(MINIBOXED)

    val tpe =
      if (specs.nonEmpty) {
        log("Specializing " + stemClass + "...\n")

        // step1: widen the class or trait to contain specialized overloads
        val scope1 = newScopeWith(widen(stemClass, specs): _*)

        // step2: create variants
        val variantClasses =
          specs map { spec =>
            val variantClass  = specialize1(stemClass, spec, scope1)

            val existing = stemClass.owner.info.decl(variantClass.name)

            // a symbol for the specialized class already exists if there's a classfile for it.
            // keeping both crashes the compiler on test/files/pos/variantClass-Function1.scala
            if (existing != NoSymbol)
              stemClass.owner.info.decls.unlink(existing)

            // TODO: overrides in the specialized class
            if (stemClass.owner.info.decls != EmptyScope)
              afterMiniboxDupl(stemClass.owner.info.decls enter variantClass)

            variantClass
        }

        // step3: adapt the stem to be an interface
        val scope2 = removeFieldsFromStem(stemClass, scope1)

        // step4: normalize methods with @miniboxed type parameters
        val scope3 = addNormalizedMembers(stemClass, scope2)

        // step5: update the stem's parents to form its new type
        val parents1 = computeNewStemParentClasses
        val tpe1 = GenPolyType(stemClass.info.typeParams, ClassInfoType(parents1, scope3, stemClass))

        reportClasses(stemClass, scope3, variantClasses)

        tpe1

      } else { // no specializations for this class/trait/object

        // step1: add specialized overrides
        val scope0 = stemClassTpe.decls.cloneScope
        val scope1 = addSpecialOverrides(Map.empty, Map.empty, stemClass, scope0)

        // step2: add deferred type tags
        val scope2 = addDeferredTypeTagImpls(stemClass, scope1)

        // step3: normalize methods with @miniboxed type parameters
        val scope3 = addNormalizedMembers(stemClass, scope2)

        // step4:
        val specializeTypeMap = parentClasses.specializeParentsTypeMapForGeneric(stemClass)
        val parents1 = stemClassTpe.parents map specializeTypeMap
        val tpe1 = GenPolyType(stemClass.info.typeParams, ClassInfoType(parents1, scope3, stemClass))

        tpe1
      }

    // make all structural refinement members private (members may be added by special overrides and normalization)
    for (mbr <- tpe.decls if mbr.isStructuralRefinementMember)
        mbr.setFlag(PROTECTED)

    tpe
  }

  /** Additional procedures necessary for specialization */
  private object specializationProcedures {

    /** Widen current scope to include specialized overloads for each method */
    def widen(stemClass: Symbol, specs: List[PartialSpec]): List[Symbol] = {

      metadata.setClassStem(stemClass, stemClass)

      // members to specialize:
      val members = stemClass.info.decls.toList
      val methods = members.filter(s => s.isMethod && !(s.isConstructor || s.isGetter || s.isSetter))
      val getters = members.filter(_.isGetter)
      val setters = members.filter(_.isSetter)
      val fields = members.filter(m => m.isTerm && !m.isMethod)

      var newMembers = List[Symbol]()

      // we make specialized specializedMembers for every member of the stemClassal class
      for (stemMethod <- methods ::: getters ::: setters if heuristics.specializableMethodInClass(stemClass, stemMethod)) {

        val specializedOverloads = new HashMap[PartialSpec, Symbol]
        val specs_filtered =
          if (heuristics.hasSpecializedArgumentsOrReturn(stemClass, stemMethod))
            specs
          else
            specs.filter(PartialSpec.isAllAnyRef(_)) // only the generic specialization

        for (spec <- specs_filtered) {

          // if this is the generic version, we keep this member
          // else we create a new overload for it
          var variantMethod = stemMethod

          if (!PartialSpec.isAllAnyRef(spec)) {
            val env: Map[Symbol, Type] = spec map {
              case (p, v) => (p, if (v == Boxed) p.tpe else storageType(p))
            }
            val specializedTypeMap = typeMappers.MiniboxSubst(env)

            variantMethod = stemMethod.cloneSymbol(stemClass)

            // https://github.com/miniboxing/miniboxing-plugin/issues/82
            // specialized parameter accessors are not accessors anymore
            // as they violate the premise that they do not take parameters
            //  ==> we clear the PARAMACCESSOR flag from these members
            if (variantMethod.isParamAccessor)
              variantMethod.resetFlag(PARAMACCESSOR)

            variantMethod setFlag MINIBOXED
            variantMethod setName (specializedName(stemMethod.name, variants.typeParamValues(stemClass, spec)))
            variantMethod modifyInfo (info => {
              // TODO: Not sure this is necessary for widening:
              val info0 = specializedTypeMap(info.asSeenFrom(variantMethod.owner.thisType, stemMethod.owner))

              // step1: Create type tags
              val localTags =
                for (tparam <- stemClass.typeParams if tparam.hasFlag(MINIBOXED) && spec(tparam) == Miniboxed)
                  yield (variantMethod.newValue(shortTypeTagName(tparam), variantMethod.pos).setInfo(ByteClass.tpe), tparam)
              metadata.localTypeTags(variantMethod) = HashMap() ++ localTags
              val tagParams = localTags.map(_._1)

              // step2: Add type tags to method signature
              val info1 =
                info0 match {
                  case MethodType(args, ret) =>
                    MethodType(tagParams ::: args, ret)
                  case PolyType(targs, MethodType(args, ret)) =>
                    val ntargs = targs.map(_.cloneSymbol(variantMethod))
                    val MethodType(nargs, nret) = MethodType(args, ret).substSym(targs, ntargs)
                    PolyType(ntargs, MethodType(tagParams ::: nargs, nret))
                  case _ => ???
                }

              // TODO: We need to clear some override flags,
              // but that's not critical for the classes to work (#41)
              // since we're past the refchecks phase :D

              info1
            })

            // step3: rewire to the correct referenced symbol, else mixin crashes
            val alias = variantMethod.alias
            if (alias != NoSymbol) {
              // Find the correct alias in a rewired class
              val baseTpe = stemClass.info.baseType(alias.owner)
              val spec2 = ((baseTpe.typeSymbol.typeParams zip baseTpe.typeArgs) flatMap {
                case (param, tpe) if param.hasFlag(MINIBOXED) =>
                  if (ScalaValueClasses.contains(tpe.typeSymbol))
                    Some((param, Miniboxed))
                  // TODO: Take outside specializations into account!
                  else if (spec.get(tpe.typeSymbol) == Some(Miniboxed))
                    Some((param, Miniboxed))
                  else
                    Some((param, Boxed))
                case _ =>
                  None
              }).toMap
              if (metadata.memberOverloads.isDefinedAt(alias) &&
                  metadata.memberOverloads(alias).isDefinedAt(spec2)) {
                variantMethod.asInstanceOf[TermSymbol].referenced = metadata.memberOverloads(alias)(spec2)
              } else
                global.error(s"""|Could not rewire paramaccessor => will crash in mixin.
                                 |base: ${variantMethod.defString}
                                 |from: ${alias.defString}
                                 |baseTpe: $baseTpe
                                 |typeParams: ${baseTpe.typeSymbol.typeParams}
                                 |typeArgs: ${baseTpe.typeArgs}
                                 |spec2: $spec2
                                 |memberOverloads for alias: ${metadata.memberOverloads.get(alias)}""".stripMargin)
            }

            newMembers ::= variantMethod
          }

          specializedOverloads(spec) = variantMethod
          metadata.memberOverloads(variantMethod) = specializedOverloads
          metadata.setMemberStem(variantMethod, stemMethod)
        }

        for (variantClass <- specs; variantMethod <- specializedOverloads get variantClass)
          // TODO: This should be Interface(), but I'll keep it as it is to avoid breackage later on
          memberSpecializationInfo(variantMethod) = ForwardTo(stemMethod)(overrider = false)
      }

      stemClass.info.decls.toList ++ newMembers
    }

    /** xxx */
    def specialize1(stemClass: Symbol, pvariantClass: PartialSpec, widenedScope: Scope): Symbol = {

      val variantClassParamValues = typeParamValues(stemClass, pvariantClass)
      val baseName = if (flag_loader_friendly) newTermName(stemClass.name.toString + "_class") else stemClass.name
      val variantClassName = specializedName(baseName, variantClassParamValues).toTypeName
      val bytecodeClass = stemClass.owner.info.decl(variantClassName)
      bytecodeClass.info // TODO: we have 5054 here, but even this doesn't work
      val variantClass = stemClass.owner.newClass(variantClassName, stemClass.pos, stemClass.flags)

      variantClass.associatedFile = stemClass.associatedFile
      currentRun.symSource(variantClass) = stemClass.sourceFile
      specializedStemClass(variantClass) = stemClass
      variantClass.resetFlag(INTERFACE)

      val pmap = ParamMap(stemClass.typeParams, variantClass)
      variantParamMap(variantClass) = pmap.map(_.swap).toMap

      val tparamUpdate: TypeEnv = pmap.map {case (s1, s2) => (s1, s2.tpeHK)}
      val envOuter: TypeEnv = pvariantClass.map {
        case (p, Boxed)     => (p, pmap(p).tpeHK)
        case (p, Miniboxed) => (p, storageType(pmap(p)))
      }

      val envInner: TypeEnv = pvariantClass.flatMap {
        case (p, Miniboxed) => Some((pmap(p), storageType(pmap(p))))
        case _ => None
      }

      // Insert the newly created symbol in our various maps that are used by
      // the tree transformer.
      specializedClasses(stemClass) += pvariantClass -> variantClass
      variantTypeEnv(variantClass) = envOuter
      classSpecialization(variantClass) = pvariantClass

      // declarations inside the specialized class - to be filled in later
      val variantClassScope = newScope
      inheritedDeferredTypeTags(variantClass) = HashMap()
      primaryDeferredTypeTags(variantClass) = HashMap()

      // create the type of the new class
      val localPvariantClass: PartialSpec = pvariantClass.map({ case (t, sp) => (pmap(t), sp)}) // Tsp -> Boxed/Miniboxed
      val specializeParents = specializeParentsTypeMapForSpec(variantClass, stemClass, localPvariantClass)
      val specializedTypeMapOuter = MiniboxSubst(pmap.map({ case (tpSym, ntpSym) => (tpSym, ntpSym.tpeHK)}))
      val specializedTypeMapInner = MiniboxSubst(envInner)
      val specializedInfoType: Type = {
        val sParents = (stemClass.info.parents ::: List(stemClass.tpe)) map {
          t => specializedTypeMapOuter(t)
        } map specializeParents

        val newTParams: List[Symbol] = stemClass.typeParams.map(pmap)
        GenPolyType(newTParams, ClassInfoType(sParents, variantClassScope, variantClass))
      }
      afterMiniboxDupl(variantClass setInfo specializedInfoType)

      // Add type tag fields for each parameter. Will be copied in specialized subclasses.
      val typeTagMap: List[(Symbol, Symbol)] =
        (for (tparam <- stemClass.typeParams if tparam.hasFlag(MINIBOXED) && pvariantClass(tparam) == Miniboxed) yield {
          val sym =
            if (stemClass.isTrait)
              variantClass.newMethodSymbol(typeTagName(variantClass, tparam), variantClass.pos, DEFERRED).setInfo(MethodType(List(), ByteTpe))
            else
              variantClass.newValue(typeTagName(variantClass, tparam), variantClass.pos, PARAMACCESSOR | PrivateLocal).setInfo(ByteTpe)

          sym setFlag MINIBOXED
          if (stemClass.isTrait) {
            primaryDeferredTypeTags(variantClass) += sym -> pmap(tparam)
            memberSpecializationInfo(sym) = DeferredTypeTag(tparam)
          }

          variantClassScope enter sym
          (sym, pmap(tparam))
        })

      // Record the new mapping for type tags to the fields carrying them
      globalTypeTags(variantClass) = typeTagMap.toMap

      // Copy the members of the stemClassal class to the specialized class.
      val newMembers: Map[Symbol, Symbol] =
        // we only duplicate methods and fields
        (for (mbr <- widenedScope.toList if (!notSpecializable(stemClass, mbr) && !(mbr.isModule || mbr.isType || mbr.isConstructor))) yield {
          val newMbr = mbr.cloneSymbol(variantClass)
          if (mbr.isMethod) {
            if (variantClassializationStemMember(mbr) == mbr)
              variantClassializationStemMember(newMbr) = newMbr
            else
              variantClassializationStemMember(newMbr) = mbr
          }
          val update = (mbr.info.params zip newMbr.info.params).toMap
          localTypeTags(newMbr) = localTypeTags.getOrElse(mbr, Map.empty).map({ case (tag, tparam) => (update(tag), tparam)})
          globalTypeTags(newMbr) = globalTypeTags(variantClass)
          (mbr, newMbr)
        }).toMap

      // Replace the info in the copied members to reflect their new class
      for ((m, newMbr) <- newMembers if !m.isConstructor) {

        newMbr setFlag MINIBOXED
        newMbr modifyInfo { info =>

          val info0 = info.substThis(stemClass, variantClass)
          val info1 = info0.asSeenFrom(variantClass.thisType, m.owner)
          val info2 =
            if (m.isTerm && !m.isMethod) {
              // this is where we specialize fields:
              specializedTypeMapInner(info1)
            } else
              info1

          info2
        }

        // TODO: Is this okay?
        variantTypeEnv(newMbr) = variantTypeEnv(variantClass)
        localTypeTags(newMbr) = newMbr.info.params.zip(localTypeTags.getOrElse(m, Map.empty).map(p => pmap(p._2))).toMap
        debug(variantClass + " entering: " + newMbr)
        variantClassScope enter newMbr
      }

      // adding the type tags as constructor arguments
      for (ctor <- widenedScope.filter(sym => sym.name == nme.CONSTRUCTOR)) {
        val newCtor = ctor.cloneSymbol(variantClass).setPos(ctor.pos)

        newCtor setFlag MINIBOXED
        newCtor modifyInfo { info =>
          val info0 = info.asSeenFrom(variantClass.thisType, ctor.owner)
          val info1 = info0.substThis(stemClass, variantClass) // Is this still necessary?
          val info2 = specializedTypeMapInner(info1)
          val tagParams = typeTagMap map (_._1.cloneSymbol(newCtor, 0))
          localTypeTags(newCtor) = tagParams.zip(typeTagMap.map(_._2)).toMap
          def transformArgs(tpe: Type): Type = tpe match {
            case MethodType(params, ret) =>
              MethodType(tpe.params, transformArgs(ret))
            case TypeRef(_, _, _) =>
              variantClass.tpe
            case _ =>
              tpe
          }
          // add the new constructor as an overload for the stemClassal constructor
          specializedMembers.get(ctor) match {
            case Some(map) => map += pvariantClass -> newCtor
            case None => specializedMembers(ctor) = HashMap(pvariantClass -> newCtor)
          }
          val info3 = transformArgs(info2)

          // dummy constructor
          if (!tagParams.isEmpty) {
            val dummyCtor = ctor.cloneSymbol(variantClass).setPos(ctor.pos)
            dummyCtor.setInfo(info3.cloneInfo(dummyCtor))
            specializedMembers.get(dummyCtor) match {
              case Some(map) => map += pvariantClass -> newCtor
              case None => specializedMembers(dummyCtor) = HashMap(pvariantClass -> newCtor)
            }
            dummyConstructors += dummyCtor
            variantClassScope enter dummyCtor
//            println("dummy constructor: " + dummyCtor.defString)
          }

          MethodType(tagParams.toList ::: info3.params, info3.resultType)
        }
        memberSpecializationInfo(newCtor) = SpecializedImplementationOf(ctor)


        variantClassScope enter newCtor
      }

      // Record how the body of these members should be generated
      for ((mbr, newMbr) <- newMembers) {
        if (mbr.isConstructor || (mbr.isTerm && !mbr.isMethod)) {
          memberSpecializationInfo(newMbr) = SpecializedImplementationOf(mbr)
        } else {
          // Check whether the method is the one that will carry the
          // implementation. If yes, find the stemClassal method from the stemClassal
          // class from which to copy the implementation. If no, find the method
          // that will have an implementation and forward to it.
          if (specializedMembers(mbr).isDefinedAt(pvariantClass)) {
            if (specializedMembers(mbr)(pvariantClass) == mbr) {
              if (mbr.hasAccessorFlag) {
                memberSpecializationInfo(newMbr) = memberSpecializationInfo.get(mbr) match {
                  case Some(ForwardTo(target)) =>
                    FieldAccessor(newMembers(target.accessed))
                  case _ =>
                    global.error("Unaccounted case: " + memberSpecializationInfo.get(mbr)); ???
                }
              } else {
                memberSpecializationInfo.get(mbr) match {
                  case Some(ForwardTo(target)) =>
                    memberSpecializationInfo(newMbr) =
                      if (!mbr.isDeferred)
                        SpecializedImplementationOf(target)
                      else
                        Interface()
                  case Some(x) =>
                    global.error("Unaccounted case: " + x)
                  case None =>
                    memberSpecializationInfo(newMbr) = SpecializedImplementationOf(mbr)
                }
              }
            } else {
              // here, we're forwarding to the all-AnyRef member, knowing that the
              // redirection algorithm will direct to the appropriate member later
              val target = newMembers(specializedMembers(mbr)(pvariantClass.isAllBoxed))
              // a forwarder will never be a tailcall itself, although it may
              // forward to a tailcall method:
              newMbr.removeAnnotation(TailrecClass)
              // since the member will be a forwarder, it can't be DEFERRED (See #85):
              newMbr.resetFlag(DEFERRED)
              memberSpecializationInfo(newMbr) = ForwardTo(target)(overrider = false)
            }
          } else {
            memberSpecializationInfo(newMbr) = SpecializedImplementationOf(mbr)
          }
        }
      }

      // populate the specializedMembers data structure for the new members also
      for ((m, newMbr) <- newMembers if (m.isMethod && !m.isConstructor)) {
        if (specializedMembers(m).isDefinedAt(pvariantClass)) {
          val newMbrMeantForSpec = newMembers(specializedMembers(m)(pvariantClass))
          if (!(specializedMembers isDefinedAt newMbrMeantForSpec)) {
            specializedMembers(newMbrMeantForSpec) = new HashMap[PartialSpec, Symbol]
            for ((s, m) <- specializedMembers(m)) {
              specializedMembers(newMbrMeantForSpec)(s) = newMembers(m)
            }
          }
          specializedMembers(newMbr) = specializedMembers(newMbrMeantForSpec)
        } else
          // member not specialized:
          specializedMembers(newMbr) = HashMap.empty
      }

      // specialized specializedMembers
      addSpecialOverrides(pvariantClass, localPvariantClass, variantClass, variantClassScope, inPlace = true)

      // deferred type tags:
      addDeferredTypeTagImpls(variantClass, variantClassScope, inPlace = true)

      // normalized members:
      addNormalizedMembers(variantClass, variantClassScope, inPlace = true)

      variantClass
    }

    // create overrides for specialized variants of the method
    def addSpecialOverrides(globalPSpec: PartialSpec, pvariantClass: PartialSpec, clazz: Symbol, scope: Scope, inPlace: Boolean = false): Scope = {

      val scope1 = if (inPlace) scope else scope.cloneScope
      val base = specializedStemClass.getOrElse(clazz, NoSymbol)

      def specializedOverriddenMembers(sym: Symbol): Symbol = {
        for (baseOSym <- sym.allOverriddenSymbols) {

          // Check we're not incorrectly overriding normalized members:
          // class B           {          def foo[@miniboxed T, U] = ???            }
          // class C extends B { override def foo[@miniboxed T, @miniboxed U] = ??? } // OK
          // class C extends D { override def foo[@miniboxed T, U] = ??? }            // NOT OK
          if (sym.typeParams.nonEmpty) {
            val tparamMap = (baseOSym.typeParams zip sym.typeParams).toMap
            val tparamMiss = baseOSym.typeParams.filter(tparam =>
              tparam.isMiniboxAnnotated && !tparamMap(tparam).isMiniboxAnnotated).map(tparamMap)
            if (tparamMiss.nonEmpty)
              currentUnit.error(sym.pos, "The " + sym + " in " + clazz + " overrides " + baseOSym + " in " + baseOSym.owner + " therefore needs to have the follwing type parameters marked with @miniboxed: " + tparamMiss.mkString(",") + ".")
          }

          if (isSpecializableClass(baseOSym.owner) && base != baseOSym.owner) {
            // here we get the base class, not the specialized class
            // therefore, the 1st step is to identify the specialized class
            val baseParent = baseOSym.owner
            val baseParentTpe = clazz.info.baseType(baseParent)
            val variantClass = PartialSpec.fromTypeInContext(baseParentTpe.asInstanceOf[TypeRef], pvariantClass)
            if (!PartialSpec.isAllAnyRef(variantClass) && specializedMembers.isDefinedAt(baseOSym)) {
              specializedMembers(baseOSym).get(variantClass) match {
                case Some(mainSym) =>
                  return mainSym
                case _ =>
              }
            }
          }
        }
        NoSymbol
      }

    if (clazz.isClass) // class or trait
      for (sym <- scope1 if sym.isMethod && !sym.isConstructor) {
        specializedOverriddenMembers(sym).toOption.foreach(oSym => {
          val localOverload = specializedMembers.get(sym).flatMap(_.get(globalPSpec)).getOrElse(NoSymbol)
          // only generate the override if we don't have an overload which matches the current symbol:
          // matching the symbol is a pain in the arse, since oSym points to the interface while localOverload
          // points to the current clazz -- TODO: we could carry newMembers and get the correspondence
          if (localOverload.name != oSym.name) {
            val overrider = oSym.cloneSymbol(clazz)
            overrider.setInfo(oSym.info.cloneInfo(overrider).asSeenFrom(clazz.thisType, oSym.owner))
            overrider.resetFlag(DEFERRED).setFlag(OVERRIDE)

            val paramUpdate = (oSym.info.params zip overrider.info.params).toMap
            val baseClazz = oSym.owner
            val baseType = clazz.info.baseType(baseClazz)
            val tparamUpdate = (baseClazz.typeParams zip baseType.typeArgs.map(_.typeSymbol)).toMap
            val typeTags = localTypeTags.getOrElse(oSym, Map.empty).map({ case (tag, tpe) => (paramUpdate(tag), tparamUpdate(tpe))})

            // copy the body to the specialized overload and let the symbol forward. There is no optimal solution
            // when using nested class variantClassialization:
            // ```
            //   abstract class C[T, U] { def foo(t: T, u: U): Any }
            //   class X[Y] { new C[Int, Y] { def foo(t: Int, u: Y) = ??? }
            // ```
            // which method should carry the body in class X_J? foo or foo_JJ?
            //  * `foo`    gets t: Int unboxed and u: Y boxed
            //  * `foo_JJ` gets both t and u as miniboxed, which is still suboptimal, but better
            localTypeTags(overrider) = typeTags

            memberSpecializationInfo.get(sym) match {
              // if sym is a forwarder to a more specialized member, let the overrider forward to
              // the the most specialized member, else we're losing optimality
              case Some(ForwardTo(moreSpec)) =>
                memberSpecializationInfo(overrider) = ForwardTo(sym)(overrider = false)

              // if sym is the most specialized version of the code, then just move it over to the
              // new overrider symbol, exactly like in the example above -- `foo_JJ`
              case _ =>
                memberSpecializationInfo(sym) = ForwardTo(sym)(overrider = false)
                memberSpecializationInfo(overrider) = SpecializedImplementationOf(sym)
            }
            specializedMembers.getOrElseUpdate(sym, collection.mutable.HashMap()) += (pvariantClass -> overrider)

            scope1 enter overrider
          }
        })
      }
      scope1
    }

    // add members derived from deferred type tags
    def addDeferredTypeTagImpls(stemClass: Symbol, scope: Scope, inPlace: Boolean = false): Scope = {
      val scope1 = if (inPlace) scope else scope.cloneScope
      if (!stemClass.isTrait) {
        val deferredTags = primaryDeferredTypeTags(stemClass) ++ inheritedDeferredTypeTags(stemClass)
        // classes satisfy the deferred tags immediately, no need to keep them
        for ((method, tparam) <- deferredTags) {
          val impl = method.cloneSymbol(stemClass).setFlag(MINIBOXED)
          impl.resetFlag(DEFERRED | ABSTRACT)
          memberSpecializationInfo(impl) = DeferredTypeTagImplementation(tparam)
          scope1 enter impl
        }
        inheritedDeferredTypeTags(stemClass).clear()
      }
      scope1
    }

    // normalize methods
    def addNormalizedMembers(stemClass: Symbol, scope: Scope, inPlace: Boolean = false): Scope = {
      val newScope = if (inPlace) scope else scope.cloneScope
      for (mbr <- scope.cloneScope)
        normalizeMembers(mbr).foreach(newScope.enter _)
      newScope
    }

    def removeFieldsFromStem(stemClass: Symbol, stemClassDecls: Scope): Scope = {
      val decls = stemClassDecls.cloneScope
      for (mbr <- decls) {
        if (mbr.isMethod) mbr.setFlag(DEFERRED)
        if ((mbr.isTerm && !mbr.isMethod) || (mbr.isConstructor))
          decls unlink mbr
      }
      // Remove the tailcall notation from members
      decls.foreach(_.removeAnnotation(TailrecClass))
      // This needs to be delayed until trees have been duplicated, else
      // instantiation will fail, since C becomes an abstract class
      if (stemClass.hasFlag(TRAIT))
        metadata.classStemTraitFlag += stemClass
      stemClass.setFlag(TRAIT | ABSTRACT)

      stemClass.setFlag(MINIBOXED)
      stemClass.resetFlag(FINAL)
      stemClass.resetFlag(CASE)

      decls
    }

    def computeNewStemParentClasses: List[Type] = {
      val artificialAnyRefReq = (
           !stemClass.isTrait
        && ((stemClassTpe.parents.size >= 1)
        &&  metadata.isClassStem(stemClassTpe.parents.head.typeSymbol)))
      val artificialAnyRef = if (artificialAnyRefReq) List(AnyRefTpe) else Nil
      artificialAnyRef ::: stemClassTpe.parents
    }

    def reportClasses(stemClass: Symbol, scope3: Scope, classes: List[Symbol]) = {
              log("  // interface:")
        log("  " + stemClass.defString + " {")
        for (decl <- scope3.toList.sortBy(_.defString))
          log(f"    ${decl.defString}%-70s")

        log("  }\n")

        classes foreach { cls =>
          log("  // specialized class:")
          log("  " + cls.defString + " {")
          for (decl <- cls.info.decls.toList.sortBy(_.defString) if !metadata.dummyConstructors(decl))
            log(f"    ${decl.defString}%-70s // ${memberSpecializationInfo.get(decl).map(_.toString).getOrElse("no info")}")
          log("  }\n")
        }
        log("\n\n")
    }
  }
}
