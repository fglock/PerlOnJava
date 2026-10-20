use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Path qw(make_path);
use File::Spec;
no warnings 'once';

my $dir = tempdir(CLEANUP => 1);
make_path(File::Spec->catdir($dir, 'CallerFrame'));

sub write_module {
    my ($relative, $source) = @_;
    my $path = File::Spec->catfile($dir, split m{/}, $relative);
    open my $fh, '>', $path or die "open $path: $!";
    print {$fh} $source or die "write $path: $!";
    close $fh or die "close $path: $!";
}

write_module('CallerFrame/Outer.pm', <<'PERL');
package CallerFrame::Outer;
use CallerFrame::Inner;
1;
PERL

write_module('CallerFrame/Inner.pm', <<'PERL');
package CallerFrame::Inner;
our $REQUIRE_CALLER = caller;
die "unexpected require caller: $REQUIRE_CALLER\n"
    unless $REQUIRE_CALLER eq 'CallerFrame::Outer';
1;
PERL

{
    local @INC = ($dir, @INC);
    delete @INC{'CallerFrame/Outer.pm', 'CallerFrame/Inner.pm'};
    my $loaded = eval 'use CallerFrame::Outer; 1';
    ok($loaded, 'nested require from compile-time use succeeds') or diag $@;
    is($CallerFrame::Inner::REQUIRE_CALLER, 'CallerFrame::Outer',
        'file-scope caller in nested require names the requiring package');
}

done_testing;
