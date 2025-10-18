use 5.38.0;
use strict;
use warnings;
use Test::More;

# Test #line directive

my $original_file = __FILE__;

my $line;
my $file;

# line 50
$line = __LINE__;
$file = __FILE__;
is( $line, 50,             "Line number is $line" );
is( $file, $original_file, "File is original file" );

{
    # This is an invalid directive because it is indented
    # line 200
    ;
}
$line = __LINE__;
$file = __FILE__;
isnt( $line, 200, "This is a comment; Line number is $line" );
is( $file, $original_file, "File is original file" );

# line 100 "testfile.pl"
$line = __LINE__;
$file = __FILE__;
is( $line, 100,           "Line number is $line" );
is( $file, 'testfile.pl', "File is '$file'" );

## # line 150 "testfile2.pl" other comments
## $line = __LINE__;
## $file = __FILE__;
## is($line, 150, "Line number is $line");
## is($file, 'testfile2.pl', "File is '$file'");

done_testing();

