use strict;
use warnings;
use utf8;
use Test::More tests => 5;

delete $::{audit_source};
$::{audit_source} = \"Value";
{
    no strict 'refs';
    *{'audit_void'} = \&{'audit_source'};
}
is(ref $::{audit_source}, 'SCALAR', 'void export retains proxy representation');

my $result;
{
    no strict 'refs';
    $result = *{'audit_value'} = \&{'audit_source'};
}
is(ref \$::{audit_source}, 'GLOB', 'value-context export upgrades original glob identity');

delete $::{audit_dangling_source};
$::{audit_dangling_source} = \"Dangling";
sub export_from_sub {
    no strict 'refs';
    *{'audit_dangling_target'} = \&{'audit_dangling_source'};
}
export_from_sub();
is(ref \$::{audit_dangling_source}, 'GLOB', 'dangling export upgrades original glob identity');
is(eval 'audit_dangling_target', 'Dangling', 'dangling export remains callable');

my %holder;
{
    no warnings 'once';
    sub { for (shift) { $_ = *audit_pvlv; $_ = 'plain'; is($_, 'plain', 'PVLV remains overwriteable') } }->($holder{slot});
}
