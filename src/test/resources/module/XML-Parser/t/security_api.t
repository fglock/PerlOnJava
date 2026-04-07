use strict;
use warnings;

use Test::More;
use XML::Parser;

# These APIs require compile-time support from libexpat.
# We test for availability at runtime and skip gracefully.

my $p = XML::Parser::Expat->new;

# BillionLaughs APIs (require libexpat >= 2.4.0 with XML_DTD)
SKIP: {
    my $has_bl = defined &XML::Parser::Expat::SetBillionLaughsAttackProtectionMaximumAmplification;
    skip "BillionLaughs API not available (libexpat < 2.4.0 or no XML_DTD)", 5
      unless $has_bl;

    # Test via Expat object methods
    ok( defined $p->billion_laughs_attack_protection_maximum_amplification(100.0),
        "set maximum amplification factor" );

    ok( defined $p->billion_laughs_attack_protection_activation_threshold(1_000_000),
        "set activation threshold" );

    # Test via XML::Parser constructor options
    my $parser = XML::Parser->new(
        BillionLaughsAttackProtectionMaximumAmplification  => 50.0,
        BillionLaughsAttackProtectionActivationThreshold   => 500_000,
    );
    isa_ok( $parser, 'XML::Parser' );

    # Parse a simple document to ensure options don't break parsing
    my $result;
    eval { $result = $parser->parse('<root><child/></root>'); };
    is( $@, '', "parse succeeds with BillionLaughs options set" );

    # Test via Expat constructor options
    my $expat = XML::Parser::Expat->new(
        BillionLaughsAttackProtectionMaximumAmplification => 200.0,
    );
    isa_ok( $expat, 'XML::Parser::Expat' );
    $expat->release;
}

# ReparseDeferral API (requires libexpat >= 2.6.0)
SKIP: {
    my $has_rd = defined &XML::Parser::Expat::SetReparseDeferralEnabled;
    skip "ReparseDeferral API not available (libexpat < 2.6.0)", 3
      unless $has_rd;

    ok( defined $p->reparse_deferral_enabled(0),
        "disable reparse deferral" );
    ok( defined $p->reparse_deferral_enabled(1),
        "enable reparse deferral" );

    # Test via XML::Parser constructor options
    my $parser = XML::Parser->new( ReparseDeferralEnabled => 0 );
    eval { $parser->parse('<root/>'); };
    is( $@, '', "parse succeeds with ReparseDeferralEnabled option" );
}

# AllocTracker APIs (require libexpat >= 2.7.2)
SKIP: {
    my $has_at = defined &XML::Parser::Expat::SetAllocTrackerMaximumAmplification;
    skip "AllocTracker API not available (libexpat < 2.7.2)", 5
      unless $has_at;

    # Test via Expat object methods
    ok( defined $p->alloc_tracker_maximum_amplification(100.0),
        "set alloc tracker maximum amplification factor" );

    ok( defined $p->alloc_tracker_activation_threshold(1_000_000),
        "set alloc tracker activation threshold" );

    # Test via XML::Parser constructor options
    my $parser = XML::Parser->new(
        AllocTrackerMaximumAmplification  => 50.0,
        AllocTrackerActivationThreshold   => 500_000,
    );
    isa_ok( $parser, 'XML::Parser' );

    # Parse a simple document to ensure options don't break parsing
    my $result;
    eval { $result = $parser->parse('<root><child/></root>'); };
    is( $@, '', "parse succeeds with AllocTracker options set" );

    # Test via Expat constructor options
    my $expat = XML::Parser::Expat->new(
        AllocTrackerMaximumAmplification => 200.0,
    );
    isa_ok( $expat, 'XML::Parser::Expat' );
    $expat->release;
}

# Error handling: methods croak on missing APIs
SKIP: {
    my $has_bl = defined &XML::Parser::Expat::SetBillionLaughsAttackProtectionMaximumAmplification;
    skip "BillionLaughs API is available, cannot test missing-API error", 1
      if $has_bl;

    eval { $p->billion_laughs_attack_protection_maximum_amplification(100.0); };
    like( $@, qr/not available/, "croak with helpful message when API unavailable" );
}

SKIP: {
    my $has_at = defined &XML::Parser::Expat::SetAllocTrackerMaximumAmplification;
    skip "AllocTracker API is available, cannot test missing-API error", 1
      if $has_at;

    eval { $p->alloc_tracker_maximum_amplification(100.0); };
    like( $@, qr/not available/, "croak with helpful message when AllocTracker API unavailable" );
}

$p->release;

done_testing;
