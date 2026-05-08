use strict;
use warnings;
use Test::More tests => 3;
use File::Path qw(make_path);

BEGIN {
    $INC{'EvalContextStackRepro/Registry.pm'} = __FILE__;
}

package EvalContextStackRepro::Registry;

use strict;
use warnings;
use Carp ();

my %IS_ROLE;
our ( $AFTER_EVAL_ROLE, $EARLY_RETURN_ROLE );

sub import {
    my $class  = shift;
    my $target = caller;

    if ( @_ == 1 && $_[0] eq 'with' ) {
        no strict 'refs';
        *{ $target . '::with' } = sub {
            $class->apply_roles_to_package( $target, @_ );
        };
        return;
    }

    $class->_declare_role($target);
}

sub _declare_role {
    my ( $class, $target ) = @_;
    $IS_ROLE{$target} = 1;
    no strict 'refs';
    *{ $target . '::requires' } = sub { return };
}

sub apply_roles_to_package {
    my ( $class, $target, @roles ) = @_;
    $class->_load_role($_) for @roles;
}

sub _load_role {
    my ( $class, $role, $version ) = @_;

    $version ||= '';
    my $stash = do { no strict 'refs'; \%{"${role}::"} };
    if ( exists $stash->{requires} ) {
        my $package = $role;
        $package =~ s{::}{/}g;
        $package .= '.pm';
        $INC{$package} ||= "added to inc by $class";
    }

    eval "use $role $version";
    Carp::confess($@) if $@;

    $AFTER_EVAL_ROLE = $role;
    if ( $IS_ROLE{$role} ) {
        $EARLY_RETURN_ROLE = $role;
        return 1;
    }

    my $requires = $role->can('requires');
    if ( !$requires ) {
        Carp::confess(
            "Only roles defined with $class may be loaded with _load_role. '$role' is not allowed."
        );
    }

    $IS_ROLE{$role} = 1;
    return 1;
}

package main;

sub write_file {
    my ( $path, $body ) = @_;
    open my $fh, '>', $path or die "open $path: $!";
    print {$fh} $body or die "write $path: $!";
    close $fh or die "close $path: $!";
}

my $root = "/tmp/perlonjava-eval-context-stack-$$";
my $lib  = "$root/lib";
make_path("$lib/EvalContextStackRepro");
unshift @INC, $lib;

write_file(
    "$lib/EvalContextStackRepro/Plugin.pm",
    <<'PLUGIN'
package EvalContextStackRepro::Plugin;
use EvalContextStackRepro::Registry;
1;
PLUGIN
);

write_file(
    "$lib/EvalContextStackRepro/Host.pm",
    <<'HOST'
package EvalContextStackRepro::Host;
use EvalContextStackRepro::Registry 'with';
with 'EvalContextStackRepro::Plugin';
sub required_method {}
1;
HOST
);

eval { EvalContextStackRepro::Registry->_load_role('EvalContextStackRepro::Plugin') };
ok( !$@, 'loading a marked package succeeds' ) or diag $@;

eval { EvalContextStackRepro::Registry->_load_role('EvalContextStackRepro::Host') };
like(
    $@,
    qr/Only roles defined with EvalContextStackRepro::Registry may be loaded/,
    'outer eval role name survives nested eval use'
) or diag(
    "after eval role=[$EvalContextStackRepro::Registry::AFTER_EVAL_ROLE], "
      . "early return role=[$EvalContextStackRepro::Registry::EARLY_RETURN_ROLE]"
);

my @begin_seen;
eval q{
    BEGIN { @begin_seen = map { $_ => eval { die } || -1 } qw(ABC XYZ); }
};
is(
    "@begin_seen",
    "ABC -1 XYZ -1",
    'BEGIN execution keeps eval lexical aliases visible'
) or diag $@;
