package com.jsdroid.who.groovy;

import android.support.annotation.Keep;

import org.apache.commons.io.FileUtils;
import org.codehaus.groovy.control.CompilationFailedException;

import java.io.File;
import java.io.IOException;

import groovy.lang.Script;

@Keep
public abstract class AndroidScript extends Script {
    public static final String VERSION = "2.0";
    private AndroidGroovyShell compiler;

    public void setCompiler(AndroidGroovyShell compiler) {
        this.compiler = compiler;
    }

    public String version() {
        return VERSION;
    }

    @Override
    public void run(File file, String[] arguments) throws CompilationFailedException, IOException {
        require(file.getPath());
    }

    public void require(String... files) throws CompilationFailedException, IOException {
        for (int i = 0; i < files.length; i++) {
            String code = FileUtils.readFileToString(new File(files[i]), "utf-8");
            AndroidScript script = evaluate(code, files[i]);
            script.run();
        }
    }

    private AndroidScript evaluate(String code, String filename) throws IOException {
//        AndroidGroovyShell childCompile = new AndroidGroovyShell(compiler.getTmpFile(), compiler.getClassLoader());
        AndroidScript script = compiler.evaluate(code, filename);
        script.setBinding(getBinding());
        script.run();
        return script;
    }

    public Object eval(String code) throws CompilationFailedException, IOException {
        return evaluate(code, null).run();
    }


}
