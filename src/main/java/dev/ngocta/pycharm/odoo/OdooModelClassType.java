package dev.ngocta.pycharm.odoo;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import com.jetbrains.python.psi.types.PyClassLikeType;
import com.jetbrains.python.psi.types.PyClassType;
import com.jetbrains.python.psi.types.PyClassTypeImpl;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;


public class OdooModelClassType extends PyClassTypeImpl {
    private OdooModelInfo myModelInfo;
    private OdooRecordSetType myRecordSetType;
    private List<PyClassLikeType> cachedAncestorTypes;

    @Nullable
    public static OdooModelClassType fromClass(@NotNull PyClass source, boolean isDefinition) {
        return fromClass(source, isDefinition ? null : OdooRecordSetType.MULTI);
    }

    @Nullable
    public static OdooModelClassType fromClass(@NotNull PyClass source, @Nullable OdooRecordSetType recordSetType) {
        OdooModelInfo info = OdooModelInfo.readFromClass(source);
        if (info != null) {
            return new OdooModelClassType(source, info, recordSetType);
        }
        return null;
    }

    private OdooModelClassType(@NotNull PyClass source, @NotNull OdooModelInfo modelInfo, OdooRecordSetType recordSetType) {
        super(source, recordSetType == null);
        myModelInfo = modelInfo;
        myRecordSetType = recordSetType;
    }

    public OdooModelClassType getOneRecord() {
        return new OdooModelClassType(myClass, myModelInfo, OdooRecordSetType.ONE);
    }

    @NotNull
    @Override
    public List<PyClassLikeType> getSuperClassTypes(@NotNull TypeEvalContext context) {
        if (myModelInfo.getInherit().isEmpty()) {
            return super.getSuperClassTypes(context);
        }
        List<PyClassLikeType> result = new LinkedList<>();
        List<PyClass> supers = getSuperClasses();
        supers.forEach(pyClass -> {
            OdooModelClassType superType = fromClass(pyClass, myRecordSetType);
            if (superType != null) {
                result.add(superType);
            }
        });
        return result;
    }

    @NotNull
    @Override
    public List<PyClassLikeType> getAncestorTypes(@NotNull TypeEvalContext context) {
//        if (cachedAncestorTypes != null) {
//            return cachedAncestorTypes;
//        }
        List<PyClassLikeType> result = new LinkedList<>();
        resolveAncestorTypes(this, context, result);
//        cachedAncestorTypes = result;
        return result;
    }

    private void resolveAncestorTypes(OdooModelClassType type, TypeEvalContext context, List<PyClassLikeType> result) {
        type.getSuperClassTypes(context).forEach(pyClassLikeType -> {
            if (pyClassLikeType instanceof OdooModelClassType && !pyClassLikeType.equals(this) && !result.contains(pyClassLikeType)) {
                OdooModelClassType odooModelClassType = (OdooModelClassType) pyClassLikeType;
                result.add(odooModelClassType);
                resolveAncestorTypes(odooModelClassType, context, result);
            }
        });
    }

    @NotNull
    private List<PyClass> getSuperClasses() {
        List<PyClass> result = new LinkedList<>();
        myModelInfo.getInherit().forEach(s -> {
            resolveSuperClasses(s, myModelInfo.getModuleName(), result);
        });
        return result;
    }

    private void resolveSuperClasses(String model, String moduleName, List<PyClass> result, Collection<PyClass> excludedClasses) {
        Project project = myClass.getProject();
        List<PyClass> pyClasses = OdooModelIndex.findModelClasses(model, moduleName, project);
        if (pyClasses.isEmpty()) {
            List<String> depends = OdooModuleIndex.getDepends(moduleName, project);
            depends.forEach(depend -> resolveSuperClasses(model, depend, result, excludedClasses));
        } else {
            result.addAll(pyClasses);
            excludedClasses.addAll(pyClasses);
        }
    }

    @Nullable
    public PsiElement findMember(@NotNull String name, PyResolveContext resolveContext, boolean inherit) {
        TypeEvalContext context = resolveContext.getTypeEvalContext();
        PyTargetExpression attExpr = myClass.findClassAttribute(name, false, context);
        if (attExpr != null) {
            return attExpr;
        }
        PyFunction funcExpr = myClass.findMethodByName(name, false, context);
        if (funcExpr != null) {
            return funcExpr;
        }
        if (inherit) {
            for (PyClassLikeType classType : getAncestorTypes(context)) {
                if (classType instanceof OdooModelClassType) {
                    PsiElement member = ((OdooModelClassType) classType).findMember(name, resolveContext, false);
                    if (member != null) {
                        return member;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public List<? extends RatedResolveResult> resolveMember(@NotNull String name, @Nullable PyExpression location, @NotNull AccessDirection direction, @NotNull PyResolveContext resolveContext, boolean inherited) {
        PsiElement element = findMember(name, resolveContext, inherited);
        if (element != null) {
            return Collections.singletonList(new RatedResolveResult(RatedResolveResult.RATE_NORMAL, element));
        }
        return super.resolveMember(name, location, direction, resolveContext, inherited);
    }

    @Override
    public @Nullable String getName() {
        return myModelInfo.getName();
    }
}