package org.sonarlint.intellij.analysis;

import com.intellij.openapi.vfs.VirtualFile;

import java.nio.charset.Charset;

public class P3cAnalysisUtils {
     public static DefaultClientInputFile createClientInputFile(VirtualFile virtualFile, String relativePath, Charset charset){
         return new DefaultClientInputFile(virtualFile, relativePath, false, charset);
     }

}
