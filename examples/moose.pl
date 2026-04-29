# A cute demonstration of Moose, the postmodern object system for Perl
#
# What better way to showcase Moose than with actual moose
# (and friends) in a little forest ecosystem?
#
# This file exists solely for demonstration and educational purposes.
# It is NOT part of the automated test suite.
#
# Running this demo:
#   ./jperl examples/moose.pl
#
# Features demonstrated:
#   - Moose attributes (has) with types, defaults, builders, and lazy
#   - Inheritance (extends)
#   - Roles (with, Moose::Role)
#   - Method modifiers (before, after, around)
#   - Type constraints (isa)
#   - Required attributes and predicates
#   - BUILD hooks
#   - Delegation (handles)

use strict;
use warnings;
use Test::More;

# ── Role: Printable ──────────────────────────────────────────────────
# Roles are like mix-ins — any class can consume them.

package Printable {
    use Moose::Role;

    requires 'describe';

    sub print_tag {
        my $self = shift;
        return "[" . ref($self) . "] " . $self->describe;
    }
}

# ── Role: Swimmable ──────────────────────────────────────────────────

package Swimmable {
    use Moose::Role;

    has swim_speed => (
        is      => 'ro',
        isa     => 'Int',
        default => 3,
    );

    sub swim {
        my $self = shift;
        return ref($self) . " paddles along at " . $self->swim_speed . " km/h";
    }
}

# ── Base class: Animal ───────────────────────────────────────────────

package Animal {
    use Moose;
    with 'Printable';

    has name => (
        is       => 'ro',
        isa      => 'Str',
        required => 1,
    );

    has sound => (
        is      => 'ro',
        isa     => 'Str',
        default => '...',
    );

    has hunger => (
        is      => 'rw',
        isa     => 'Int',
        default => 5,
    );

    sub speak {
        my $self = shift;
        return $self->name . ' says "' . $self->sound . '"';
    }

    sub eat {
        my ($self, $food) = @_;
        my $h = $self->hunger - 1;
        $h = 0 if $h < 0;
        $self->hunger($h);
        return $self->name . " munches on $food (hunger: " . $self->hunger . ")";
    }

    sub describe {
        my $self = shift;
        return $self->name . " the " . ref($self);
    }
}

# ── The star of the show: Moose! ─────────────────────────────────────

package Moose::Animal {
    use Moose;
    extends 'Animal';
    with 'Swimmable';

    has antler_points => (
        is      => 'ro',
        isa     => 'Int',
        default => 10,
    );

    has '+sound' => (default => 'GRUNT');

    has '+swim_speed' => (default => 8);

    # A lazy attribute with a builder
    has title => (
        is      => 'ro',
        isa     => 'Str',
        lazy    => 1,
        builder => '_build_title',
    );

    sub _build_title {
        my $self = shift;
        my $pts = $self->antler_points;
        return $pts >= 12 ? "Majestic"
             : $pts >= 8  ? "Regal"
             :              "Young";
    }

    # Method modifier: around wraps the parent method
    around describe => sub {
        my ($orig, $self) = @_;
        return $self->title . " " . $self->$orig()
             . " (" . $self->antler_points . "-point antlers)";
    };
}

# ── Squirrel ──────────────────────────────────────────────────────────

package Squirrel {
    use Moose;
    extends 'Animal';

    has acorns => (
        is      => 'rw',
        isa     => 'Int',
        default => 0,
    );

    has '+sound' => (default => 'CHITTER');

    sub gather {
        my ($self, $n) = @_;
        $n //= 1;
        $self->acorns($self->acorns + $n);
        return $self->name . " gathered $n acorn(s) (total: " . $self->acorns . ")";
    }

    # Method modifier: after runs code after the parent method
    after eat => sub {
        my $self = shift;
        $self->acorns($self->acorns + 1);   # always stashes one for later
    };
}

# ── Owl ───────────────────────────────────────────────────────────────

package Owl {
    use Moose;
    extends 'Animal';

    has wisdom => (
        is      => 'ro',
        isa     => 'Int',
        default => 42,
    );

    has '+sound' => (default => 'HOO HOO');

    # Method modifier: before runs code before the parent method
    before speak => sub {
        my $self = shift;
        # Owls blink wisely before speaking
    };

    sub ponder {
        my $self = shift;
        return $self->name . " ponders the meaning of life... (wisdom: " . $self->wisdom . ")";
    }
}

# ── Forest: uses delegation ──────────────────────────────────────────

package Forest {
    use Moose;

    has name => (
        is       => 'ro',
        isa      => 'Str',
        required => 1,
    );

    has residents => (
        is      => 'ro',
        isa     => 'ArrayRef[Animal]',
        default => sub { [] },
        handles => {
            add_resident    => 'push',
            resident_count  => 'count',
            all_residents   => 'elements',
        },
        traits => ['Array'],
    );

    sub roll_call {
        my $self = shift;
        return join(", ", map { $_->name } @{ $self->residents });
    }

    sub describe {
        my $self = shift;
        return $self->name . " forest (" . scalar(@{ $self->residents }) . " residents)";
    }
}

# ══════════════════════════════════════════════════════════════════════
# Let's bring the forest to life!
# ══════════════════════════════════════════════════════════════════════

package main;

subtest 'Creating animals with Moose' => sub {
    my $bullwinkle = Moose::Animal->new(
        name          => 'Bullwinkle',
        antler_points => 14,
    );

    is($bullwinkle->name,          'Bullwinkle',  'moose has a name');
    is($bullwinkle->sound,         'GRUNT',        'moose grunts');
    is($bullwinkle->antler_points, 14,             'moose has 14-point antlers');
    is($bullwinkle->title,         'Majestic',     'lazy builder computed title');
    ok($bullwinkle->isa('Animal'),                 'moose isa Animal');
    ok($bullwinkle->does('Swimmable'),             'moose does Swimmable');
};

subtest 'Inheritance and default overrides' => sub {
    my $rocky = Squirrel->new(name => 'Rocky');

    is($rocky->sound,  'CHITTER', 'squirrel default sound');
    is($rocky->acorns, 0,         'starts with no acorns');

    like($rocky->gather(3), qr/gathered 3 acorn/, 'gathering acorns');
    is($rocky->acorns, 3, 'acorn count updated');
};

subtest 'Method modifiers' => sub {
    # 'around' on describe
    my $moose = Moose::Animal->new(name => 'Morris', antler_points => 6);
    like($moose->describe, qr/Young Morris the Moose::Animal/, 'around modifier decorates describe');

    # 'after' on eat — squirrel stashes an extra acorn
    my $squirrel = Squirrel->new(name => 'Hazel');
    $squirrel->eat('walnut');
    is($squirrel->acorns, 1, 'after modifier stashed an acorn during eat');
};

subtest 'Roles' => sub {
    my $moose = Moose::Animal->new(name => 'Magnus', antler_points => 12);

    # Printable role
    like($moose->print_tag, qr/\[Moose::Animal\]/, 'Printable role adds print_tag');

    # Swimmable role
    is($moose->swim_speed, 8, 'moose overrides default swim speed');
    like($moose->swim, qr/paddles along at 8/, 'Swimmable role adds swim');

    # Owl doesn't swim
    my $owl = Owl->new(name => 'Archimedes');
    ok(!$owl->can('swim'), 'owl cannot swim (no Swimmable role)');
    ok($owl->does('Printable'), 'owl does Printable');
};

subtest 'Type constraints' => sub {
    # Hunger must be an Int
    my $moose = Moose::Animal->new(name => 'Monty');
    $moose->hunger(3);
    is($moose->hunger, 3, 'hunger set to Int');

    eval { $moose->hunger('very hungry') };
    ok($@, 'type constraint rejects non-Int for hunger');
};

subtest 'Forest with delegation' => sub {
    my $forest = Forest->new(name => 'Whispering Pines');

    my $moose    = Moose::Animal->new(name => 'Bullwinkle', antler_points => 14);
    my $squirrel = Squirrel->new(name => 'Rocky');
    my $owl      = Owl->new(name => 'Archimedes');

    $forest->add_resident($moose);
    $forest->add_resident($squirrel);
    $forest->add_resident($owl);

    is($forest->resident_count, 3, 'forest has 3 residents');
    is($forest->roll_call, 'Bullwinkle, Rocky, Archimedes', 'roll call lists everyone');
};

subtest 'A day in the forest' => sub {
    my $bullwinkle = Moose::Animal->new(name => 'Bullwinkle', antler_points => 14);
    my $rocky      = Squirrel->new(name => 'Rocky');
    my $archimedes = Owl->new(name => 'Archimedes');

    # Morning activities
    like($bullwinkle->speak,      qr/GRUNT/,       'moose grunts good morning');
    like($rocky->gather(5),       qr/gathered 5/,   'squirrel gathers acorns');
    like($archimedes->ponder,     qr/meaning of life/, 'owl ponders');

    # Lunchtime
    like($bullwinkle->eat('willow bark'), qr/munches on willow bark/, 'moose eats');
    like($bullwinkle->swim,               qr/paddles along/,          'moose goes for a swim');

    # Evening report
    like($bullwinkle->print_tag, qr/Majestic Bullwinkle/, 'moose print tag');
    like($rocky->print_tag,      qr/\[Squirrel\] Rocky/,  'squirrel print tag');
    like($archimedes->print_tag, qr/\[Owl\] Archimedes/,  'owl print tag');
};

done_testing();
