use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);

my $dir = tempdir(CLEANUP => 1);
open my $fh, '>', "$dir/CLASS.pm" or die "open CLASS.pm: $!";
print {$fh} <<'PERL';
package CLASS;
sub import {
    my $caller = caller;
    no strict 'refs';
    *{"${caller}::CLASS"} = sub () { $caller };
}
1;
PERL
close $fh or die "close CLASS.pm: $!";

{
    local @INC = ($dir, @INC);
    my $ok = eval q{
        package ImportedConstant;
        use CLASS;
        sub bar { 23 }
        (CLASS eq 'ImportedConstant' && CLASS->bar == 23) ? 1 : 0;
    };
    ok($ok, 'imported CLASS constant retains its importing package for method calls')
        or diag $@;
}

done_testing;
