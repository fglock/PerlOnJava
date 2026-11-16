use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);

BEGIN {
    eval {
        require Moose;
        require Moose::Role;
        require Moose::Util::MetaRole;
        require namespace::autoclean;
        1;
    } or plan skip_all => 'Moose and namespace::autoclean are required';
}

my $module_root = tempdir(CLEANUP => 1);
my $module_dir = File::Spec->catdir($module_root, qw(Issue2043 NestedMetarole));
make_path($module_dir);

my @module_sources = (
    [
        File::Spec->catfile($module_dir, 'InnerRole.pm'),
        <<'PERL',
package Issue2043::NestedMetarole::InnerRole;
use Moose::Role;
use namespace::autoclean;
sub helper { 'inner' }
1;
PERL
    ],
    [
        File::Spec->catfile($module_dir, 'OuterRole.pm'),
        <<'PERL',
package Issue2043::NestedMetarole::OuterRole;
use Moose::Role;
use Issue2043::NestedMetarole::InnerRole;
use namespace::autoclean;
override wrap => sub {
    my $method = super;
    return $method;
};
1;
PERL
    ],
);

for my $module_source (@module_sources) {
    my ($path, $source) = @$module_source;
    open my $module, '>', $path or die "open $path: $!";
    print {$module} $source or die "write $path: $!";
    close $module or die "close $path: $!";
}

unshift @INC, $module_root;

{
    package Issue2043::NestedMetarole::Consumer;
    use Moose;
    sub base { 'base' }
}

my ($result, $call_error);
{
    local $@;
    eval {
        require Issue2043::NestedMetarole::OuterRole;
        Moose::Util::MetaRole::apply_metaroles(
            for => 'Issue2043::NestedMetarole::Consumer',
            class_metaroles => {
                wrapped_method => ['Issue2043::NestedMetarole::OuterRole'],
            },
        );
        Issue2043::NestedMetarole::Consumer->meta
            ->add_before_method_modifier('base', sub { });
        $result = Issue2043::NestedMetarole::Consumer->new->base;
        1;
    } or $call_error = $@;
}

ok(!$call_error, 'a nested namespace cleanup preserves the imported metarole super call')
    or diag($call_error);
is($result, 'base', 'the wrapped method still returns its base result');

done_testing();
