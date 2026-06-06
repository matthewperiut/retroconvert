package com.periut.retroconvert;

import java.util.Map;

import org.objectweb.asm.commons.Remapper;

/**
 * ASM remapper backed by the {@link TokenMap}.
 *
 * Member tokens are globally unique, so methods/fields are renamed by name
 * alone regardless of owner — this also covers @Shadow members declared on the
 * mod's own mixin classes. Annotation string values and string constants flow
 * through {@link #mapValue}, which covers refmap-less mixins
 * (@Inject(method = "method_1977")) and reflection by intermediary name.
 *
 * For the owner-aware library entries (argo etc.) the owner's superclasses and
 * interfaces are walked through the classes of the jar being converted, so a
 * mod class extending a renamed library type still resolves.
 */
public final class RetroRemapper extends Remapper {
	/** super/interface info for every class inside the jar being converted */
	public static final class ClassMeta {
		final String superName;
		final String[] interfaces;

		public ClassMeta(String superName, String[] interfaces) {
			this.superName = superName;
			this.interfaces = interfaces;
		}
	}

	private final TokenMap map;
	private final Map<String, ClassMeta> jarHierarchy;

	public RetroRemapper(TokenMap map, Map<String, ClassMeta> jarHierarchy) {
		this.map = map;
		this.jarHierarchy = jarHierarchy;
	}

	@Override
	public String map(String internalName) {
		return map.mapClass(internalName);
	}

	@Override
	public String mapMethodName(String owner, String name, String descriptor) {
		String mapped = map.methods.get(name);
		if (mapped != null) {
			return mapped;
		}
		mapped = findOwnerMethod(owner, name, descriptor);
		return mapped != null ? mapped : name;
	}

	@Override
	public String mapFieldName(String owner, String name, String descriptor) {
		String mapped = map.fields.get(name);
		if (mapped != null) {
			return mapped;
		}
		mapped = findOwnerField(owner, name);
		return mapped != null ? mapped : name;
	}

	@Override
	public String mapInvokeDynamicMethodName(String name, String descriptor) {
		String mapped = map.methods.get(name);
		return mapped != null ? mapped : name;
	}

	@Override
	public Object mapValue(Object value) {
		if (value instanceof String) {
			return map.remapString((String) value);
		}
		return super.mapValue(value);
	}

	private String findOwnerMethod(String owner, String name, String descriptor) {
		String mapped = map.ownerMethods.get(owner + '\0' + name + '\0' + descriptor);
		if (mapped != null) {
			return mapped;
		}
		ClassMeta meta = jarHierarchy.get(owner);
		if (meta != null) {
			if (meta.superName != null) {
				mapped = findOwnerMethod(meta.superName, name, descriptor);
				if (mapped != null) {
					return mapped;
				}
			}
			for (String itf : meta.interfaces) {
				mapped = findOwnerMethod(itf, name, descriptor);
				if (mapped != null) {
					return mapped;
				}
			}
		}
		return null;
	}

	private String findOwnerField(String owner, String name) {
		String mapped = map.ownerFields.get(owner + '\0' + name);
		if (mapped != null) {
			return mapped;
		}
		ClassMeta meta = jarHierarchy.get(owner);
		if (meta != null && meta.superName != null) {
			return findOwnerField(meta.superName, name);
		}
		return null;
	}
}
