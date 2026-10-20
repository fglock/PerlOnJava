use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Path qw(make_path);
use File::Spec;
no warnings 'once';

my $dir = tempdir(CLEANUP => 1);
make_path(File::Spec->catdir($dir, 'Data', 'Util'));
make_path(File::Spec->catdir($dir, 'Catmandu', 'Fix'));

sub write_module {
    my ($relative, $source) = @_;
    my $path = File::Spec->catfile($dir, split m{/}, $relative);
    open my $fh, '>', $path or die "open $path: $!";
    print {$fh} $source or die "write $path: $!";
    close $fh or die "close $path: $!";
}

write_module('Catmandu/Fix/rkd_name.pm', <<'PERL');
package Catmandu::Fix::rkd_name;
use Data::Util;
1;
PERL

write_module('Data/Util.pm', <<'PERL');
package Data::Util;
our $TESTING_PERL_ONLY = 1;
require 'Data/Util/PurePerl.pm' if $TESTING_PERL_ONLY;
1;
PERL

write_module('Data/Util/PurePerl.pm', <<'PERL');
package Data::Util::PurePerl;
our $REQUIRE_CALLER = caller;
die "Don't use Data::Util::PurePerl directly\n"
    unless $REQUIRE_CALLER eq 'Data::Util';
package Data::Util;
1;
PERL

{
    local @INC = ($dir, @INC);
    delete @INC{'Catmandu/Fix/rkd_name.pm', 'Data/Util.pm', 'Data/Util/PurePerl.pm'};
    my $loaded = eval 'use Catmandu::Fix::rkd_name; 1';
    ok($loaded, 'nested require through compile-time use succeeds') or diag $@;
    is($Data::Util::PurePerl::REQUIRE_CALLER, 'Data::Util',
        'file-scope caller in PurePerl nested require names Data::Util');
}

done_testing;
