use strict;
use warnings;
use utf8;
use Test::More tests => 8;

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
is(ref \$::{audit_dangling_target}, 'GLOB', 'second dangling export upgrades target glob identity');

my $audit_undef_error = eval q{
    use constant audit_named_constant => 1;
    BEGIN { $main::audit_named_constant_ref = \&audit_named_constant }
    undef &$main::audit_named_constant_ref;
    $main::audit_named_constant_ref->();
    1;
};
like($@, qr/^Undefined subroutine &main::audit_named_constant called/, 'undef keeps named constant CV diagnostic');

{
    no warnings 'io';
    no strict 'refs';
    readline *{'audit_last_fh'};
    my $last_fh = "${^LAST_FH}";
    eval '*audit_last_fh if 0';
    is("${^LAST_FH}", $last_fh, 'no-op eval glob does not clear LAST_FH');
}

my %holder;
{
    no warnings 'once';
    sub { for (shift) { $_ = *audit_pvlv; $_ = 'plain'; is($_, 'plain', 'PVLV remains overwriteable') } }->($holder{slot});
}
