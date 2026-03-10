package examples;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.*;

/**
 * Example: Using Image::ExifTool module from Java for batch processing.
 * 
 * For repeated execution with different files, using the Image::ExifTool 
 * Perl module directly is more efficient than running the CLI script 
 * repeatedly. The module is loaded once, then methods can be called 
 * for each file.
 * 
 * Prerequisites:
 *   Download and extract ExifTool in the PerlOnJava root directory:
 *     cd PerlOnJava
 *     curl -LO https://exiftool.org/Image-ExifTool-13.44.tar.gz
 *     tar xzf Image-ExifTool-13.44.tar.gz
 * 
 * Run the Perl version with:
 *   ./gradlew run --args='examples/ExifToolExample.pl'
 * 
 * Or compile and run the Java version:
 *   1. Build the fat jar:
 *        make
 *      or:
 *        ./gradlew shadowJar
 *   2. Compile this example:
 *        javac -cp build/libs/perlonjava-*-all.jar examples/ExifToolExample.java
 *   3. Run:
 *        java -cp build/libs/perlonjava-*-all.jar:examples examples.ExifToolExample
 */
public class ExifToolExample {
    
    public static void main(String[] args) throws Exception {
        // Initialize PerlOnJava
        PerlLanguageProvider.resetAll();
        
        // Add ExifTool lib to @INC
        RuntimeArray inc = GlobalVariable.getGlobalArray("main::INC");
        RuntimeArray.push(inc, new RuntimeScalar("Image-ExifTool-13.44/lib"));
        
        // Load Image::ExifTool and define helper subroutine
        String initScript = """
            use strict;
            use warnings;
            use Image::ExifTool;
            
            our $exif = Image::ExifTool->new();
            
            sub process_image {
                my ($file) = @_;
                my $info = $exif->ImageInfo($file, qw(Make Model DateTimeOriginal));
                print "File: $file\\n";
                for my $tag (sort keys %$info) {
                    print "  $tag: $info->{$tag}\\n";
                }
                print "\\n";
            }
            1;
            """;
        
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<init>";
        options.code = initScript;
        
        System.out.println("Loading Image::ExifTool...");
        PerlLanguageProvider.executePerlCode(options, true);
        System.out.println("Ready.\n");
        
        // Process multiple images by calling the Perl subroutine
        String[] images = {
            "Image-ExifTool-13.44/t/images/Canon.jpg",
            "Image-ExifTool-13.44/t/images/Nikon.jpg"
        };
        
        RuntimeScalar processImage = GlobalVariable.getGlobalCodeRef("main::process_image");
        
        for (String image : images) {
            RuntimeArray callArgs = new RuntimeArray();
            RuntimeArray.push(callArgs, new RuntimeScalar(image));
            RuntimeCode.apply(processImage, callArgs, RuntimeContextType.VOID);
        }
    }
}
