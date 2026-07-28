use strict;
use warnings;

use Test::More;
use ExtUtils::MakeMaker;

for my $module (qw(DynaLoader bytes)) {
    (my $filename = "$module.pm") =~ s{::}{/}g;
    require $filename;

    my $runtime_version = $module->VERSION;
    my $file_version = MM->parse_version($INC{$filename});

    is(
        "$runtime_version",
        "$file_version",
        "$module runtime version matches its module file",
    );
}

done_testing;
