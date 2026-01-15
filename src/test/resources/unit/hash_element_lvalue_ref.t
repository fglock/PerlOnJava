use strict;
use warnings;
use Test::More;

# Perl behavior: taking a reference to a hash element autovivifies the element,
# and the reference aliases the actual hash slot.
{
    my $h = {};
    my $r = \$$h{a};

    ok(defined($r), 'got a reference to hash element');
    ok(!defined($$r), 'new hash element is initially undef');

    $h->{a} = 'x';
    is($$r, 'x', 'reference sees assignment through hash');

    $$r = 'y';
    is($h->{a}, 'y', 'assignment through reference updates hash element');
}

# Same semantics for blessed hashrefs (as used by ExifTool objects)
{
    my $self = bless {}, 'X';
    my $r = \$$self{EXIF_DATA};

    ok(defined($r), 'got a reference to blessed hash element');
    ok(!defined($$r), 'new blessed hash element is initially undef');

    $self->{EXIF_DATA} = 'abc';
    is($$r, 'abc', 'reference sees assignment through blessed hash');

    $$r = 'def';
    is($self->{EXIF_DATA}, 'def', 'assignment through reference updates blessed hash element');
}

done_testing();
