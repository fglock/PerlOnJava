#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 5;
use File::Temp qw(tempdir);
use File::Spec;

my $tmp = tempdir(CLEANUP => 1);

my $installer_pm = File::Spec->catfile($tmp, 'RequireScopeEndInstaller.pm');
open my $installer_fh, '>', $installer_pm or die "open $installer_pm: $!";
print {$installer_fh} <<'EOPM';
package RequireScopeEndInstaller;

sub install {
    my ($target) = @_;
    no strict 'refs';
    *{"${target}::generated"} = sub { 'generated' };
}

1;
EOPM
close $installer_fh;

my $order_pm = File::Spec->catfile($tmp, 'RequireScopeEndOrder.pm');
open my $order_fh, '>', $order_pm or die "open $order_pm: $!";
print {$order_fh} <<'EOPM';
package RequireScopeEndOrder;
use strict;
use warnings;

our @EVENTS;

BEGIN {
    require B::Hooks::EndOfScope;
    B::Hooks::EndOfScope::on_scope_end(sub {
        push @RequireScopeEndOrder::EVENTS, 'scope_end';
    });
}

push @EVENTS, 'runtime';

1;
EOPM
close $order_fh;

my $target_pm = File::Spec->catfile($tmp, 'RequireScopeEndTarget.pm');
open my $target_fh, '>', $target_pm or die "open $target_pm: $!";
print {$target_fh} <<'EOPM';
package RequireScopeEndTarget;
use strict;
use warnings;
use namespace::autoclean;
use RequireScopeEndInstaller ();

RequireScopeEndInstaller::install(__PACKAGE__);

1;
EOPM
close $target_fh;

{
    local @INC = ($tmp, @INC);

    my $order_loaded = eval { require RequireScopeEndOrder; 1 };
    ok($order_loaded, 'required module with on_scope_end loads')
        or diag "\$@ = $@";
    is_deeply(
        \@RequireScopeEndOrder::EVENTS,
        ['scope_end', 'runtime'],
        'on_scope_end from a required file fires before top-level runtime statements',
    );

    my $target_loaded = eval { require RequireScopeEndTarget; 1 };
    ok($target_loaded, 'required module with namespace::autoclean loads')
        or diag "\$@ = $@";
    ok(
        RequireScopeEndTarget->can('generated'),
        'namespace::autoclean does not remove methods installed by top-level runtime code',
    );
    is(
        RequireScopeEndTarget->generated,
        'generated',
        'runtime-installed method remains callable',
    );
}
