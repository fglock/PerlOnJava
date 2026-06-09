#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Spec;

# Regression: do/require return the last *runtime* statement value.
# use/no pragmas and sub declarations are compile-time and must not
# clobber an earlier runtime assignment such as our $VERSION = '...'.

my $dir = tempdir(CLEANUP => 1);
require File::Basename;
require File::Path;

sub write_mod {
    my ($name, $body) = @_;
    my $path = File::Spec->catfile($dir, split m{::}, $name) . '.pm';
    File::Path::make_path(File::Basename::dirname($path));
    open my $fh, '>', $path or die $!;
    print {$fh} $body;
    close $fh;
    return $path;
}

{
    my $path = write_mod('Ok::Mod', <<'PM');
package Ok::Mod;
our $VERSION = '9.87';
use strict;
use warnings;
sub foo {}
PM
    {
        local @INC = ($dir, @INC);
        my $inc_key = 'Ok/Mod.pm';
        delete $INC{$inc_key};
        my $do = do $path;
        is($do, '9.87', 'do returns last runtime assignment');
        delete $INC{$inc_key};
        my $req = eval { require Ok::Mod; 1 };
        is($req, 1, 'require succeeds without trailing 1;');
        is($Ok::Mod::VERSION, '9.87', 'package version preserved');
    }
}

{
    my $path = write_mod('Bad::Mod', <<'PM');
package Bad::Mod;
our $VERSION = '1.0';
0;
PM
    {
        local @INC = ($dir, @INC);
        delete $INC{'Bad/Mod.pm'};
        my $ok = eval { require Bad::Mod; 1 };
        ok(!$ok, 'explicit false last statement still fails require');
        like($@, qr/did not return a true value/, 'expected require error');
    }
}

done_testing();
