use strict;
use warnings;
use Test::More;

my $module = 'UnitRePragmaScope_' . $$;
my $file = "$module.pm";

open my $fh, '>', $file or die "cannot create $file: $!";
print {$fh} "require re; re->import('/x'); 1;\n";
close $fh or die "cannot close $file: $!";

my $ok = eval "use lib q{.}; use $module; q{ab} =~ /a b/";
is($@, '', 'use of a module that imports re succeeds');
ok($ok, 're pragma imported by use applies to the remaining eval source');

unlink $file or die "cannot remove $file: $!";

done_testing();
