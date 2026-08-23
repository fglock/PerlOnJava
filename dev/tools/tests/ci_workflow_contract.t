use strict;
use warnings;

use CPAN::Meta::YAML;
use File::Spec;
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $workflow = File::Spec->catfile($root, qw(.github workflows gradle.yml));
my $documents = CPAN::Meta::YAML->read($workflow);
ok($documents, 'workflow parses as YAML') or BAIL_OUT(CPAN::Meta::YAML->errstr);
is(scalar @$documents, 1, 'workflow contains one YAML document');
my $parsed = $documents->[0];

is_deeply([validate_contract($parsed)], [],
    'checked-in workflow satisfies the complete CI contract');

my @negative = (
    ['missing Ubuntu matrix entry', sub {
        $_[0]{jobs}{build}{strategy}{matrix}{os} = '[windows-latest]';
    }, qr/matrix platforms/],
    ['missing step timeout', sub {
        delete step($_[0], 'checkout-source')->{'timeout-minutes'};
    }, qr/checkout-source timeout/],
    ['allow-failure bypass', sub {
        step($_[0], 'threads-linux')->{'continue-on-error'} = 'true';
    }, qr/continue-on-error/],
    ['wrong Make target', sub {
        step($_[0], 'threads-windows')->{run} = 'make test-threads';
    }, qr/threads-windows run/],
    ['pinned non-latest Perl corpus', sub {
        step($_[0], 'checkout-perl5')->{with}{ref} = 'v5.44.0';
    }, qr/checkout-perl5 must not pin ref/],
    ['weakened pull-request trigger', sub {
        $_[0]{on}{pull_request}{branches} = '[ "develop" ]';
    }, qr/pull_request branches/],
    ['matrix allow-failure strategy', sub {
        $_[0]{jobs}{build}{strategy}{'fail-fast'} = 'false';
    }, qr/fail-fast false/],
    ['duplicate step identity', sub {
        step($_[0], 'setup-java')->{id} = 'checkout-source';
    }, qr/duplicate step id/],
);

for my $case (@negative) {
    my ($name, $mutate, $pattern) = @$case;
    my $copy = JSON::PP->new->decode(JSON::PP->new->encode($parsed));
    $mutate->($copy);
    my @errors = validate_contract($copy);
    ok(@errors, "$name is rejected");
    like(join("\n", @errors), $pattern, "$name has a structural diagnostic");
}

done_testing;

sub validate_contract {
    my ($doc) = @_;
    my @error;
    unless (ref($doc) eq 'HASH') {
        return 'workflow root is not a mapping';
    }
    push @error, 'workflow name changed'
        unless ($doc->{name} // '') eq 'Java CI with Gradle';
    push @error, 'workflow permissions changed'
        if exists $doc->{permissions};

    my $on = $doc->{on};
    if (ref($on) ne 'HASH') {
        push @error, 'workflow triggers are not a mapping';
    } else {
        my @triggers = sort keys %$on;
        push @error, 'workflow triggers changed'
            unless join(',', @triggers) eq 'pull_request,push';
        for my $event (qw(push pull_request)) {
            my $branches = ref($on->{$event}) eq 'HASH'
                ? $on->{$event}{branches} : undef;
            push @error, "$event branches are not exactly master"
                unless same_set([flow_list($branches)], ['master']);
        }
    }

    my $jobs = $doc->{jobs};
    if (ref($jobs) ne 'HASH' || join(',', sort keys %$jobs) ne 'build') {
        push @error, 'workflow must retain the sole build job id';
        return @error unless ref($jobs) eq 'HASH' && ref($jobs->{build}) eq 'HASH';
    }
    my $job = $jobs->{build};
    push @error, 'stable job/check name changed'
        unless ($job->{name} // '') eq 'build (${{ matrix.os }})';
    push @error, 'job runs-on no longer binds matrix.os'
        unless ($job->{'runs-on'} // '') eq '${{ matrix.os }}';
    positive_timeout($job->{'timeout-minutes'}, 'job', 180, \@error);
    push @error, 'job permissions changed' if exists $job->{permissions};
    push @error, 'job continue-on-error bypass is forbidden'
        if exists $job->{'continue-on-error'};

    my $strategy = $job->{strategy};
    if (ref($strategy) ne 'HASH' || ref($strategy->{matrix}) ne 'HASH') {
        push @error, 'matrix strategy is missing';
    } else {
        push @error, 'matrix platforms are not exact Ubuntu and Windows latest'
            unless same_set([flow_list($strategy->{matrix}{os})],
                [qw(ubuntu-latest windows-latest)]);
        if (exists $strategy->{'fail-fast'}
                && scalar_false($strategy->{'fail-fast'})) {
            push @error, 'matrix fail-fast false bypass is forbidden';
        }
        push @error, 'matrix include/exclude bypass is forbidden'
            if exists $strategy->{include} || exists $strategy->{exclude};
    }

    my $steps = $job->{steps};
    unless (ref($steps) eq 'ARRAY') {
        push @error, 'steps are not an array';
        return @error;
    }
    my @expected_order = qw(checkout-source setup-java setup-gradle build-windows
        threads-windows build-linux checkout-perl5 threads-linux sbom-linux
        upload-sbom upload-failure);
    my %seen;
    my @actual_order;
    for my $step (@$steps) {
        unless (ref($step) eq 'HASH') {
            push @error, 'step is not a mapping';
            next;
        }
        my $id = $step->{id} // '';
        push @actual_order, $id;
        push @error, "invalid step id $id" unless $id =~ /\A[a-z][a-z0-9-]*\z/;
        push @error, "duplicate step id $id" if $seen{$id}++;
        push @error, "$id continue-on-error bypass is forbidden"
            if exists $step->{'continue-on-error'};
        positive_timeout($step->{'timeout-minutes'}, $id, 60, \@error);
    }
    push @error, 'required step order or identities changed'
        unless join(',', @actual_order) eq join(',', @expected_order);

    my %expected = (
        'checkout-source' => ['Checkout source', '', 'actions/checkout@v4', ''],
        'setup-java' => ['Set up JDK 24', '', 'actions/setup-java@v4', ''],
        'setup-gradle' => ['Setup Gradle', '', 'gradle/actions/setup-gradle@v4', ''],
        'build-windows' => ['Build with Make (Windows)', q{runner.os == 'Windows'}, '', 'make ci'],
        'threads-windows' => ['Run focused Perl thread gate (Windows)', q{runner.os == 'Windows'}, '', 'make test-threads-windows'],
        'build-linux' => ['Build with Make (Linux)', q{runner.os == 'Linux'}, '', 'make ci'],
        'checkout-perl5' => ['Checkout latest Perl thread compatibility corpus', q{runner.os == 'Linux'}, 'actions/checkout@v4', ''],
        'threads-linux' => ['Run Perl thread compatibility gate', q{runner.os == 'Linux'}, '', 'make test-threads'],
        'sbom-linux' => ['Generate SBOM (Linux only)', q{runner.os == 'Linux'}, '', 'make sbom'],
        'upload-sbom' => ['Upload SBOM artifacts', q{runner.os == 'Linux'}, 'actions/upload-artifact@v4', ''],
        'upload-failure' => ['Upload test results', 'failure()', 'actions/upload-artifact@v4', ''],
    );
    for my $id (@expected_order) {
        my $step = first_step($steps, $id);
        unless ($step) {
            push @error, "$id step is missing";
            next;
        }
        my ($name, $condition, $uses, $run) = @{$expected{$id}};
        push @error, "$id name changed" unless ($step->{name} // '') eq $name;
        push @error, "$id condition changed" unless ($step->{if} // '') eq $condition;
        push @error, "$id action changed" unless ($step->{uses} // '') eq $uses;
        push @error, "$id run changed" unless ($step->{run} // '') eq $run;
    }

    my $java = first_step($steps, 'setup-java');
    my $source_checkout = first_step($steps, 'checkout-source');
    push @error, 'source checkout must consume the triggering revision without overrides'
        if exists $source_checkout->{with};
    push @error, 'setup-java contract changed'
        unless ref($java->{with}) eq 'HASH'
            && ($java->{with}{'java-version'} // '') eq '24'
            && ($java->{with}{distribution} // '') eq 'temurin';
    for my $id (qw(build-windows threads-windows)) {
        my $step = first_step($steps, $id);
        push @error, "$id Windows shell contract changed"
            unless ($step->{shell} // '') eq 'cmd'
                && ref($step->{env}) eq 'HASH'
                && ($step->{env}{GRADLE_OPTS} // '') eq '-Dorg.gradle.daemon=false';
    }

    my $perl5 = first_step($steps, 'checkout-perl5');
    my $perl_with = $perl5->{with};
    if (ref($perl_with) ne 'HASH') {
        push @error, 'checkout-perl5 with mapping is missing';
    } else {
        push @error, 'checkout-perl5 repository changed'
            unless ($perl_with->{repository} // '') eq 'Perl/perl5';
        push @error, 'checkout-perl5 path changed'
            unless ($perl_with->{path} // '') eq 'perl5';
        push @error, 'checkout-perl5 must not pin ref; latest default branch is required'
            if exists $perl_with->{ref};
        my @sparse = grep { length } map { trim($_) }
            split /\r?\n/, ($perl_with->{'sparse-checkout'} // '');
        push @error, 'checkout-perl5 sparse corpus changed'
            unless join(',', @sparse) eq join(',', qw(t dist/threads
                dist/threads-shared dist/Thread-Queue dist/Thread-Semaphore));
    }

    my $sbom = first_step($steps, 'upload-sbom');
    push @error, 'SBOM artifact contract changed'
        unless ref($sbom->{with}) eq 'HASH'
            && ($sbom->{with}{name} // '') eq 'sbom'
            && ($sbom->{with}{'retention-days'} // '') eq '90'
            && grep { $_ eq 'build/reports/sbom.json' }
                block_lines($sbom->{with}{path});
    my $failure = first_step($steps, 'upload-failure');
    my @failure_paths = block_lines($failure->{with}{path});
    for my $path (qw(build/reports/tests/test/ build/test-results/test/
            build/reports/threads/ build/test-results/testThreadsWindows/)) {
        push @error, "failure artifact omits $path"
            unless grep { $_ eq $path } @failure_paths;
    }
    push @error, 'failure artifact matrix identity changed'
        unless ($failure->{with}{name} // '') eq 'test-results-${{ matrix.os }}';
    return @error;
}

sub step {
    my ($doc, $id) = @_;
    return first_step($doc->{jobs}{build}{steps}, $id)
        // die "fixture cannot find step $id\n";
}

sub first_step {
    my ($steps, $id) = @_;
    return (grep { ref($_) eq 'HASH' && ($_->{id} // '') eq $id } @$steps)[0];
}

sub positive_timeout {
    my ($value, $label, $maximum, $errors) = @_;
    push @$errors, "$label timeout is missing, non-positive, or above $maximum"
        unless defined($value) && $value =~ /\A[1-9][0-9]*\z/ && $value <= $maximum;
}

sub flow_list {
    my ($value) = @_;
    return unless defined $value;
    return @$value if ref($value) eq 'ARRAY';
    return unless !ref($value) && $value =~ /\A\s*\[(.*)\]\s*\z/s;
    return map { my $item = trim($_); $item =~ s/\A['"]|['"]\z//g; $item }
        split /,/, $1;
}

sub block_lines {
    my ($value) = @_;
    return grep { length } map { trim($_) } split /\r?\n/, ($value // '');
}

sub same_set {
    my ($left, $right) = @_;
    return join("\0", sort @$left) eq join("\0", sort @$right);
}

sub scalar_false {
    my ($value) = @_;
    return !defined($value) || $value eq '' || $value eq '0'
        || lc($value) eq 'false' || lc($value) eq 'no';
}

sub trim {
    my ($value) = @_;
    $value =~ s/\A\s+//;
    $value =~ s/\s+\z//;
    return $value;
}
