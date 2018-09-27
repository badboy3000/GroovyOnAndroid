package com.jsdroid.who.groovy;

import android.util.Log;

import com.android.dx.Version;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.cf.CfOptions;
import com.android.dx.dex.cf.CfTranslator;
import com.android.dx.dex.code.PositionList;
import com.android.dx.dex.file.ClassDefItem;
import com.android.dx.dex.file.DexFile;

import org.apache.commons.io.FileUtils;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.BytecodeProcessor;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import dalvik.system.DexClassLoader;
import groovy.lang.GroovyClassLoader;
import groovyjarjarasm.asm.ClassWriter;

public class AndroidGroovyShell {

    private static final String DEX_IN_JAR_NAME = "classes.dex";
    private static final Attributes.Name CREATED_BY = new Attributes.Name("Created-By");

    private final DexOptions dexOptions;
    private final CfOptions cfOptions;

    private final File tmpFile;
    private final ClassLoader classLoader;


    public File getTmpFile() {
        return tmpFile;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public AndroidGroovyShell(File tmpDir, ClassLoader parent) {
        tmpFile = tmpDir;
        classLoader = parent;
        dexOptions = new DexOptions();
        dexOptions.targetApiLevel = 13;
        cfOptions = new CfOptions();
        cfOptions.positionInfo = PositionList.LINES;
        cfOptions.localInfo = true;
        cfOptions.strictNameCheck = true;
        cfOptions.optimize = false;
        cfOptions.optimizeListFile = null;
        cfOptions.dontOptimizeListFile = null;
        cfOptions.statistics = false;
        init();

    }

    public AndroidScript evaluate(File file) throws CompilationFailedException, IOException {

        return evaluate(FileUtils.readFileToString(file, "utf-8"), file.getPath());
    }

    public AndroidScript evaluate(String scriptText) throws CompilationFailedException, IOException {
        return evaluate(scriptText, null);
    }

    Set<String> classNames;

    CompilerConfiguration config;
    JGroovyClassLoader jGroovyClassLoader;

    private void init() {
        classNames = new LinkedHashSet<String>();

        config = new CompilerConfiguration();
        config.setScriptBaseClass(AndroidScript.class.getName());
        config.setTargetDirectory(tmpFile);
        config.setClasspath(".");
        jGroovyClassLoader = new JGroovyClassLoader(this.classLoader, config);

    }

    /**
     * 执行代码
     *
     * @param scriptText
     * @return
     */
    public synchronized AndroidScript evaluate(String scriptText, String filename) throws CompilationFailedException, IOException {
        classNames.clear();
        final DexFile dexFile = new DexFile(dexOptions);
        config.setBytecodePostprocessor(new BytecodeProcessor() {
            @Override
            public byte[] processBytecode(ClassNode classNode, byte[] bytes) {
//                ClassDefItem classDefItem = CfTranslator.translate(new DirectClassFile(bytes, s+".class", false), bytes, cfOptions, dexOptions, dexFile);
                String pkgName = classNode.getPackageName();
                String className = classNode.getName();
                String filePath = null;
                if (pkgName != null) {
                    classNode.getPackageName().replace(".", "/");
                    pkgName = pkgName.replace(".", "/");
                    className = className.substring(pkgName.length() + 1);
                    filePath = pkgName + "/" + className + ".class";
                } else {
                    filePath = className + ".class";
                }
                ClassDefItem classDefItem = CfTranslator.translate(filePath, bytes, cfOptions, dexOptions);
                dexFile.add(classDefItem);
                classNames.add(classNode.getName());
                return bytes;
            }
        });

        //解析代码，加载所有类
        if (filename == null) {
            jGroovyClassLoader.parseClass(scriptText);
        } else {
            jGroovyClassLoader.parseClass(scriptText, filename);
        }
        Log.e("woca", "evaluate: ok!");
        byte[] dalvikBytecode;
        try {
            //将dexFile编码为dex数据
            dalvikBytecode = dexFile.toDex(new OutputStreamWriter(new ByteArrayOutputStream()), false);
        } catch (IOException e) {
            throw e;
        }
        //动态加载类
        Map<String, Class> classes = defineDynamic(classNames, dalvikBytecode);

        AndroidScript script = null;
        for (Class scriptClass : classes.values()) {
            Log.e("shell", "compile class:" + scriptClass.getName());
            //如果解析的类为Script，则结束运行
            if (AndroidScript.class.isAssignableFrom(scriptClass)) {
                try {
                    script = (AndroidScript) scriptClass.newInstance();
                    script.setCompiler(this);
                } catch (Exception e) {
                }
                break;
            }
        }
        return script;
    }


    /**
     * 动态加载类
     *
     * @param classNames
     * @param dalvikBytecode
     * @return
     */
    private Map<String, Class> defineDynamic(Set<String> classNames, byte[] dalvikBytecode) {
        File tmpDex = new File(tmpFile, UUID.randomUUID().toString() + ".jar");
        Map<String, Class> result = new LinkedHashMap<String, Class>();
        try {
            FileOutputStream fos = null;
            JarOutputStream jar = null;
            //将dex写入jar
            try {
                fos = new FileOutputStream(tmpDex);
                jar = new JarOutputStream(fos, makeManifest());
                JarEntry classes = new JarEntry(DEX_IN_JAR_NAME);
                classes.setSize(dalvikBytecode.length);
                jar.putNextEntry(classes);
                jar.write(dalvikBytecode);
                jar.closeEntry();
                jar.finish();
            } finally {
                if (jar != null) {
                    jar.flush();
                    jar.close();
                }
                if (fos != null) {
                    fos.flush();
                    fos.close();
                }
            }
            DexClassLoader loader = new DexClassLoader(tmpDex.getAbsolutePath(), tmpFile.getAbsolutePath(), null, classLoader);
            for (String className : classNames) {
                result.put(className, loader.loadClass(className));
            }
            return result;
        } catch (Throwable e) {
            Log.e("DynamicLoading", "Unable to load class", e);
        } finally {
            tmpDex.delete();
        }
        return null;
    }

    private static Manifest makeManifest() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attribs = manifest.getMainAttributes();
        attribs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attribs.put(CREATED_BY, "dx " + Version.VERSION);
        attribs.putValue("Dex-Location", DEX_IN_JAR_NAME);
        return manifest;
    }

    private class JGroovyClassLoader extends GroovyClassLoader {


        public JGroovyClassLoader(ClassLoader loader, CompilerConfiguration config) {
            super(loader, config);
        }

        @Override
        public Class parseClass(String text, String fileName) throws CompilationFailedException {
            return super.parseClass(text, fileName);
        }

        @Override
        protected ClassCollector createCollector(CompilationUnit unit, SourceUnit su) {
            InnerLoader loader = AccessController.doPrivileged(new PrivilegedAction<InnerLoader>() {
                public InnerLoader run() {
                    return new InnerLoader(JGroovyClassLoader.this);
                }
            });
            return new ClassCollector(loader, unit, su) {
                @Override
                protected Class onClassNode(ClassWriter classWriter, ClassNode classNode) {
                    try {
                        Class result = super.onClassNode(classWriter, classNode);
                        return result;
                    } catch (Exception e) {
                    }

                    return null;
                }
            };
        }


    }

}
