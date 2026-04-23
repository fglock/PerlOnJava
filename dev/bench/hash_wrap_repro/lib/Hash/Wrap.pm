package Hash::Wrap;

# ABSTRACT: create on-the-fly objects from hashes

use 5.01000;

use strict;
use warnings;

use Scalar::Util;
use Digest::MD5;
our $VERSION = '1.09';

our @EXPORT = qw[ wrap_hash ];

our @CARP_NOT = qw( Hash::Wrap );
our $DEBUG    = 0;

# copied from Damian Conway's PPR: PerlIdentifier
use constant PerlIdentifier => qr/\A([^\W\d]\w*+)\z/;

# use builtin::export_lexically if available
use constant HAS_LEXICAL_SUBS => $] >= 5.038;
use if HAS_LEXICAL_SUBS, 'experimental', 'builtin';
use if HAS_LEXICAL_SUBS, 'builtin';

our %REGISTRY;

sub _croak {
    require Carp;
    goto \&Carp::croak;
}

sub _croak_class_method {
    my ( $class, $method ) = @_;
    $class = ref( $class ) || $class;
    _croak( qq[Can't locate class method "$method" via package "$class"] );
}

sub _croak_object_method {
    my ( $object, $method ) = @_;
    my $class = Scalar::Util::blessed( $object ) || ref( $object ) || $object;
    _croak( qq[Can't locate object method "$method" via package "$class"] );
}

sub _find_symbol {
    my ( $package, $symbol, $reftype ) = @_;

    no strict 'refs';    ## no critic (ProhibitNoStrict)
    my $candidate = *{"$package\::$symbol"}{SCALAR};

    return $$candidate
      if defined $candidate
      && 2 == grep { defined $_->[0] && defined $_->[1] ? $_->[0] eq $_->[1] : 1 }
      [ $reftype->[0], Scalar::Util::reftype $candidate ],
      [ $reftype->[1], Scalar::Util::reftype $$candidate ];

    _croak( "Unable to find scalar \$$symbol in class $package" );
}

# this is called only if the method doesn't exist.
sub _generate_accessor {
    my ( $hash_class, $class, $key ) = @_;

    my %dict = (
        key   => $key,
        class => $class,
    );

    my $code    = $REGISTRY{$hash_class}{accessor_template};
    my $coderef = _compile_from_tpl( \$code, \%dict );
    _croak_about_code( \$code, 'accessor' )
      if $@;

    return $coderef;
}

sub _generate_predicate {
    my ( $hash_class, $class, $key ) = @_;

    my %dict = (
        key   => $key,
        class => $class,
    );

    my $code    = $REGISTRY{$hash_class}{predicate_template};
    my $coderef = _compile_from_tpl( \$code, \%dict );
    _croak_about_code( \$code, 'predicate' )
      if $@;

    return $coderef;
}

sub _autoload {
    my ( $hash_class, $method, $object ) = @_;

    my ( $class, $key ) = $method =~ /(.*)::(.*)/;

    _croak_class_method( $object, $key )
      unless Scalar::Util::blessed( $object );

    if ( exists $REGISTRY{$hash_class}{predicate_template}
        && $key =~ /^has_(.*)/ )
    {
        return _generate_predicate( $hash_class, $class, $1 );
    }

    _croak_object_method( $object, $key )
      unless $REGISTRY{$hash_class}{validate}->( $object, $key );

    _generate_accessor( $hash_class, $class, $key );
}

sub _can {
    my ( $self, $key, $CLASS ) = @_;

    my $class = Scalar::Util::blessed( $self );
    return () if !defined $class;

    if ( !exists $self->{$key} ) {

        if ( exists $Hash::Wrap::REGISTRY{$class}{methods}{$key} ) {
            ## no critic (ProhibitNoStrict)
            no strict 'refs';
            my $method = "${class}::$key";
            return *{$method}{CODE};
        }
        return ();
    }

    my $method = "${class}::$key";

    ## no critic (ProhibitNoStrict PrivateSubs)
    no strict 'refs';
    return *{$method}{CODE}
      || Hash::Wrap::_generate_accessor( $CLASS, $class, $key );
}

sub import {    ## no critic(ExcessComplexity)
    shift;

    my @imports = @_;
    push @imports, @EXPORT unless @imports;

    my @return;

    for my $args ( @imports ) {
        if ( !ref $args ) {
            _croak( "$args is not exported by ", __PACKAGE__ )
              unless grep { /$args/ } @EXPORT;    ## no critic (BooleanGrep)

            $args = { -as => $args };
        }

        elsif ( 'HASH' ne ref $args ) {
            _croak( 'argument to ', __PACKAGE__, '::import must be string or hash' )
              unless grep { /$args/ } @EXPORT;    ## no critic (BooleanGrep)
        }
        else {
            # make a copy as it gets modified later on
            $args = {%$args};
        }

        _croak( 'cannot mix -base and -class' )
          if !!$args->{-base} && exists $args->{-class};

        $DEBUG = $ENV{HASH_WRAP_DEBUG} // delete $args->{-debug};

        # -as may be explicitly 'undef' to indicate use in a standalone class
        $args->{-as} = 'wrap_hash' unless exists $args->{-as};
        my $name = delete $args->{-as};

        my $target = delete $args->{-into} // caller;

        if ( defined $name ) {

            if ( defined( my $reftype = Scalar::Util::reftype( $name ) ) ) {
                _croak( '-as must be undefined or a string or a reference to a scalar' )
                  if $reftype ne 'SCALAR'
                  && $reftype ne 'VSTRING'
                  && $reftype ne 'REF'
                  && $reftype ne 'GLOB'
                  && $reftype ne 'LVALUE'
                  && $reftype ne 'REGEXP';

                $args->{-as_scalar_ref} = $name;

            }

            elsif ( $name eq '-return' ) {
                $args->{-as_return} = 1;
            }
        }

        if ( $args->{-base} ) {
            _croak( q{don't use -as => -return with -base} )
              if $args->{-as_return};
            $args->{-class} = $target;
            $args->{-new}   = 1 if !exists $args->{-new};
            _build_class( $target, $name, $args );
        }

        else {
            _build_class( $target, $name, $args );
            if ( defined $name ) {
                my $sub = _build_constructor( $target, $name, $args );
                if ( $args->{-as_return} ) {
                    push @return, $sub;
                }
                elsif ( $args->{-lexical} ) {
                    _croak( "Perl >= v5.38 is required for -lexical; current perl is $^V" )
                      unless HAS_LEXICAL_SUBS;
                    builtin::export_lexically( $name, $sub );
                }
            }
        }

        # clean out known attributes
        delete @{$args}{
            qw[ -as -as_return -as_scalar_ref -base -class -clone
              -copy -defined -exists -immutable -lexical -lockkeys -lvalue
              -methods -new -predicate -recurse -undef ]
        };

        if ( keys %$args ) {
            _croak( 'unknown options passed to ', __PACKAGE__, '::import: ', join( ', ', keys %$args ) );
        }
    }

    return @return;
}

sub _build_class {    ## no critic(ExcessComplexity)
    my ( $target, $name, $attr ) = @_;

    # in case we're called inside a recursion and the recurse count
    # has hit zero, default behavior is no recurse, so remove it so
    # the attr signature computed below isn't contaminated by a
    # useless -recurse => 0 attribute.
    if ( exists $attr->{-recurse} ) {
        _croak( '-recurse must be a number' )
          unless Scalar::Util::looks_like_number( $attr->{-recurse} );
        delete $attr->{-recurse} if $attr->{-recurse} == 0;
    }

    if ( !defined $attr->{-class} ) {

        ## no critic (ComplexMappings)
        my @class = map {
            ( my $key = $_ ) =~ s/-//;
            ( $key, defined $attr->{$_} ? $attr->{$_} : '<undef>' )
        } sort keys %$attr;

        $attr->{-class} = join q{::}, 'Hash::Wrap::Class', Digest::MD5::md5_hex( @class );
    }

    elsif ( $attr->{-class} eq '-target' || $attr->{-class} eq '-caller' ) {
        _croak( "can't set -class => '@{[ $attr->{-class} ]}' if '-as' is not a plain string" )
          if ref $name;
        $attr->{-class} = $target . q{::} . $name;
    }

    my $class = $attr->{-class};

    return $class if defined $REGISTRY{$class};
    my $rentry = $REGISTRY{$class} = { methods => {} };

    my %closures;
    my @BODY;
    my %dict = (
        class                 => $class,
        signature             => q{},
        body                  => \@BODY,
        autoload_attr         => q{},
        validate_inline       => 'exists $self->{\<<KEY>>}',
        validate_method       => 'exists $self->{$key}',
        set                   => '$self->{q[\<<KEY>>]} = $_[0] if @_;',
        return_value          => '$self->{q[\<<KEY>>]}',
        recursion_constructor => q{},
        predicate_template    => q{},
    );

    if ( $attr->{-lvalue} ) {
        if ( $] lt '5.016000' ) {
            _croak( 'lvalue accessors require Perl 5.16 or later' )
              if $attr->{-lvalue} < 0;
        }
        else {
            $dict{autoload_attr} = q[: lvalue];
            $dict{signature}     = q[: lvalue];
        }
    }

    if ( $attr->{-undef} ) {
        $dict{validate_method} = q[ 1 ];
        $dict{validate_inline} = q[ 1 ];
    }

    if ( $attr->{-exists} ) {
        $dict{exists} = $attr->{-exists} =~ PerlIdentifier ? $1 : 'exists';
        push @BODY, q[ sub <<EXISTS>> { exists $_[0]->{$_[1] } } ];
        $rentry->{methods}{ $dict{exists} } = undef;
    }

    if ( $attr->{-defined} ) {
        $dict{defined} = $attr->{-defined} =~ PerlIdentifier ? $1 : 'defined';
        push @BODY, q[ sub <<DEFINED>> { defined $_[0]->{$_[1] } } ];
        $rentry->{methods}{ $dict{defined} } = undef;
    }

    if ( $attr->{-immutable} ) {
        $dict{set} = <<'END';
  Hash::Wrap::_croak( q[Modification of a read-only value attempted])
    if @_;
END
    }

    if ( $attr->{-recurse} ) {

        # decrement recursion limit.  It's infinite recursion if
        # -recurse < 0; always set to -1 so we keep using the same
        # class.  Note that -recurse will never be zero upon entrance
        # of this block, as -recurse => 0 is removed from the
        # attributes way upstream.

        $dict{recurse_limit} = --$attr->{-recurse} < 0 ? -1 : $attr->{-recurse};

        $dict{quoted_key} = 'q[\<<KEY>>]';
        $dict{hash_value} = '$self->{<<QUOTED_KEY>>}';

        $dict{recurse_wrap_hash} = '$<<CLASS>>::recurse_into_hash->( <<HASH_VALUE>> )';

        $dict{return_value} = <<'END';
 'HASH' eq (Scalar::Util::reftype( <<HASH_VALUE>> ) // q{})
        && ! Scalar::Util::blessed( <<HASH_VALUE>> )
      ? <<WRAP_HASH_ENTRY>>
      : <<HASH_VALUE>>;
END
        if ( $attr->{-copy} ) {

            if ( $attr->{-immutable} ) {
                $dict{wrap_hash_entry} = <<'END';
                 do { Hash::Util::unlock_ref_value( $self, <<QUOTED_KEY>> );
                      <<HASH_VALUE>> = <<RECURSE_WRAP_HASH>>;
                      Hash::Util::lock_ref_value( $self, <<QUOTED_KEY>> );
                      <<HASH_VALUE>>;
                    }
END
            }
            else {
                $dict{wrap_hash_entry} = '<<HASH_VALUE>> = <<RECURSE_WRAP_HASH>>';
            }

        }
        else {
            $dict{wrap_hash_entry} = '<<RECURSE_WRAP_HASH>>';
        }

        # do a two-step initialization of the constructor.  If
        # the initialization sub is stored in $recurse_into_hash, and then
        # $recurse_into_hash is set to the actual constructor I worry that
        # Perl may decide to garbage collect the setup subroutine while it's
        # busy setting $recurse_into_hash.  So, store the
        # initialization sub in something other than $recurse_into_hash.

        $dict{recursion_constructor} = <<'END';
our $recurse_into_hash;
our $setup_recurse_into_hash = sub {
      require Hash::Wrap;
      ( $recurse_into_hash ) = Hash::Wrap->import ( { %$attr, -as => '-return',
                                                      -recurse => <<RECURSE_LIMIT>> } );
      goto &$recurse_into_hash;
};
$recurse_into_hash = $setup_recurse_into_hash;
END

        my %attr = ( %$attr, -recurse => --$attr->{-recurse} < 0 ? -1 : $attr->{-recurse}, );
        delete @attr{qw( -as_scalar_ref -class -base -as )};
        $closures{'$attr'} = \%attr;
    }

    if ( $attr->{-predicate} ) {
        $dict{predicate_template} = <<'END';
our $predicate_template = q[
  package \<<CLASS>>;

  use Scalar::Util ();

  sub has_\<<KEY>> {
    my $self = shift;

    Hash::Wrap::_croak_class_method( $self, 'has_\<<KEY>>' )
        unless Scalar::Util::blessed( $self );

   return exists $self->{\<<KEY>>};
  }

  $Hash::Wrap::REGISTRY{methods}{'has_\<<KEY>>'} = undef;

  \&has_\<<KEY>>;
];
END
    }

    my $class_template = <<'END';
package <<CLASS>>;

<<CLOSURES>>

use Scalar::Util ();

our $validate = sub {
    my ( $self, $key ) = @_;
    return <<VALIDATE_METHOD>>;
};

<<RECURSION_CONSTRUCTOR>>

our $accessor_template = q[
  package \<<CLASS>>;

  use Scalar::Util ();

  sub \<<KEY>> <<SIGNATURE>> {
    my $self = shift;

    Hash::Wrap::_croak_class_method( $self, '\<<KEY>>' )
        unless Scalar::Util::blessed( $self );

    Hash::Wrap::_croak_object_method( $self, '\<<KEY>>' )
        unless ( <<VALIDATE_INLINE>> );

   <<SET>>

   return <<RETURN_VALUE>>;
  }
  \&\<<KEY>>;
];

<<PREDICATE_TEMPLATE>>


<<BODY>>

our $AUTOLOAD;
sub AUTOLOAD <<AUTOLOAD_ATTR>> {
    goto &{ Hash::Wrap::_autoload( q[<<CLASS>>], $AUTOLOAD, $_[0] ) };
}

sub DESTROY { }

sub can {
    return Hash::Wrap::_can( @_, q[<<CLASS>>] );
}

1;
END

    _compile_from_tpl( \$class_template, \%dict, keys %closures ? \%closures : () )
      or _croak_about_code( \$class_template, "class $class" );

    if ( !!$attr->{-new} ) {
        my $lname = $attr->{-new} =~ PerlIdentifier ? $1 : 'new';
        _build_constructor( $class, $lname, { %$attr, -as_method => 1 } );
    }

    if ( $attr->{-methods} ) {

        my $methods = $attr->{-methods};
        _croak( '-methods option value must be a hashref' )
          unless 'HASH' eq ref $methods;

        for my $mth ( keys %$methods ) {
            _croak( "method name '$mth' is not a valid Perl identifier" )
              if $mth !~ PerlIdentifier;

            my $code = $methods->{$mth};
            _croak( qq{value for method "$mth" must be a coderef} )
              unless 'CODE' eq ref $code;
            no strict 'refs';    ## no critic (ProhibitNoStrict)
            *{"${class}::${mth}"} = $code;
        }

        $rentry->{methods}{$_} = undef for keys %$methods;
    }

    push @CARP_NOT, $class;
    $rentry->{accessor_template}
      = _find_symbol( $class, 'accessor_template', [ 'SCALAR', undef ] );

    if ( $attr->{-predicate} ) {
        $rentry->{predicate_template}
          = _find_symbol( $class, 'predicate_template', [ 'SCALAR', undef ] );
    }

    $rentry->{validate} = _find_symbol( $class, 'validate', [ 'REF', 'CODE' ] );

    Scalar::Util::weaken( $rentry->{validate} );

    return $class;
}

sub _build_constructor {    ## no critic (ExcessComplexity)
    my ( $package, $name, $args ) = @_;

    # closure for user provided clone sub
    my %closures;

    _croak( 'cannot mix -copy and -clone' )
      if exists $args->{-copy} && exists $args->{-clone};

    my @USE;
    my %dict = (
        package              => $package,
        constructor_name     => $name,
        use                  => \@USE,
        package_return_value => '1;',
    );

    $dict{class}
      = $args->{-as_method}
      ? 'shift;'
      : 'q[' . $args->{-class} . '];';

    my @copy = (
        'Hash::Wrap::_croak(q{the argument to <<PACKAGE>>::<<CONSTRUCTOR_NAME>> must not be an object})',
        '  if Scalar::Util::blessed( $hash );',
    );

    if ( $args->{-copy} ) {
        push @copy, '$hash = { %{ $hash } };';
    }

    elsif ( exists $args->{-clone} ) {

        if ( 'CODE' eq ref $args->{-clone} ) {
            $closures{'clone'} = $args->{-clone};
            # overwrite @copy, as the clone sub could take an object.
            @copy = (
                'state $clone = $CLOSURES->{clone};',
                '$hash = $clone->($hash);',
                'Hash::Wrap::_croak(q{the custom clone routine for <<PACKAGE>> returned an object instead of a plain hash})',
                '  if Scalar::Util::blessed( $hash );',
            );
        }
        else {
            push @USE,  q[use Storable ();];
            push @copy, '$hash = Storable::dclone $hash;';
        }
    }

    $dict{copy} = join "\n", @copy;

    $dict{lock} = do {
        my @eval;

        if ( defined( my $opts = $args->{-immutable} || undef ) ) {

            push @USE, q[use Hash::Util ();];

            if ( 'ARRAY' eq ref $opts ) {
                _croak( "-immutable: attribute name ($_) is not a valid Perl identifier" )
                  for grep { $_ !~ PerlIdentifier } @{$opts};

                push @eval,
                  'Hash::Util::lock_keys_plus(%$hash, qw{ ' . join( q{ }, @{$opts} ) . ' });',
                  '@{$hash}{Hash::Util::hidden_keys(%$hash)} = ();',
                  ;
            }

            push @eval, 'Hash::Util::lock_hash(%$hash)';
        }
        elsif ( defined( $opts = $args->{-lockkeys} || undef ) ) {

            push @USE, q[use Hash::Util ();];

            if ( 'ARRAY' eq ref $args->{-lockkeys} ) {
                _croak( "-lockkeys: attribute name ($_) is not a valid Perl identifier" )
                  for grep { $_ !~ PerlIdentifier } @{ $args->{-lockkeys} };

                push @eval,
                  'Hash::Util::lock_keys_plus(%$hash, qw{ ' . join( q{ }, @{ $args->{-lockkeys} } ) . ' });';
            }
            elsif ( $args->{-lockkeys} ) {

                push @eval, 'Hash::Util::lock_keys(%$hash)';
            }
        }

        join( "\n", @eval );

    };

    # return the constructor sub from the factory and don't insert the
    # name into the package namespace
    if ( $args->{-as_scalar_ref} || $args->{-as_return} || $args->{-lexical} ) {
        $dict{package_return_value} = q{};
        $dict{constructor_name}     = q{};
    }

    #<<< no tidy
    my $code = <<'ENDCODE';
    package <<PACKAGE>>;

    <<USE>>
    use Scalar::Util ();

    no warnings 'redefine';

    sub <<CONSTRUCTOR_NAME>> (;$) {
      my $class = <<CLASS>>
      my $hash = shift // {};

      Hash::Wrap::_croak( 'argument to <<PACKAGE>>::<<CONSTRUCTOR_NAME>> must be a hashref' )
        if  'HASH' ne Scalar::Util::reftype($hash);
      <<COPY>>
      bless $hash, $class;
      <<LOCK>>
    }
    <<PACKAGE_RETURN_VALUE>>

ENDCODE
    #>>>

    my $result = _compile_from_tpl( \$code, \%dict, keys %closures ? \%closures : () )
      || _croak_about_code( \$code, "constructor (as $name) subroutine" );

    # caller asked for a coderef to be stuffed into a scalar
    ${$name} = $result if $args->{-as_scalar_ref};
    return $result;
}

sub _croak_about_code {
    my ( $code, $what, $error ) = @_;
    $error //= $@;
    _line_number_code( $code );
    _croak( qq[error compiling $what: $error\n$$code] );
}

sub _line_number_code {
    my ( $code ) = @_;
    chomp( $$code );
    $$code .= "\n";
    my $space = length( $$code =~ tr/\n// );
    my $line  = 0;
    $$code =~ s/^/sprintf "%${space}d: ", ++$line/emg;
}

sub _compile_from_tpl {
    my ( $code, $dict, $closures ) = @_;

    if ( defined $closures && %$closures ) {

        # add code to create lexicals if the keys begin with a q{$}
        $dict->{closures} = join( "\n",
            map { "my $_ = \$CLOSURES->{'$_'};" }
              grep { substr( $_, 0, 1 ) eq q{$} }
              keys %$closures );
    }

    _interpolate( $code, $dict );

    if ( $DEBUG ) {
        my $lcode = $$code;
        _line_number_code( \$lcode );
        print STDERR $lcode;
    }

    _clean_eval( $code, exists $dict->{closures} ? $closures : () );

}

# eval in a clean lexical space.
sub _clean_eval {
    ## no critic (StringyEval RequireCheckingReturnValueOfEval )
    if ( @_ > 1 ) {
        ## no critic (UnusedVars)
        my $CLOSURES = $_[1];
        eval( ${ $_[0] } );
    }
    else {
        eval( ${ $_[0] } );
    }

}

sub _interpolate {
    my ( $tpl, $dict, $work ) = @_;
    $work = { loop => {} } unless defined $work;

    $$tpl =~ s{(\\)?\<\<(\w+)\>\>
              }{
                  if ( defined $1 ) {
                     "<<$2>>";
                  }
                  else {
                    my $key = lc $2;
                    my $v = $dict->{$key};
                    if ( defined $v ) {
                        $v = join( "\n", @$v )
                          if 'ARRAY' eq ref $v;

                        _croak( "circular interpolation loop detected for $key" )
                          if $work->{loop}{$key}++;
                        _interpolate( \$v, $dict, $work );
                        --$work->{loop}{$key};
                    $v;
                    }
                    else {
                        q{};
                    }
                }
              }gex;
    return;
}

1;

#
# This file is part of Hash-Wrap
#
# This software is Copyright (c) 2017 by Smithsonian Astrophysical Observatory.
#
# This is free software, licensed under:
#
#   The GNU General Public License, Version 3, June 2007
#

__END__

=pod

=for :stopwords Diab Jerius Smithsonian Astrophysical Observatory getter

=head1 NAME

Hash::Wrap - create on-the-fly objects from hashes

=head1 VERSION

version 1.09

=head1 SYNOPSIS

  use Hash::Wrap;

  my $result = wrap_hash( { a => 1 } );
  print $result->a;  # prints
  print $result->b;  # throws

  # import two constructors, <cloned> and <copied> with different behaviors.
  use Hash::Wrap
    { -as => 'cloned', clone => 1},
    { -as => 'copied', copy => 1 };

  my $cloned = cloned( { a => 1 } );
  print $cloned->a;

  my $copied = copied( { a => 1 } );
  print $copied->a;

  # don't pollute your namespace
  my $wrap;
  use Hash::Wrap { -as => \$wrap};
  my $obj = $wrap->( { a => 1 } );

  # apply constructors to hashes two levels deep into the hash
  use Hash::Wrap { -recurse => 2 };

  # apply constructors to hashes at any level
  use Hash::Wrap { -recurse => -1 };

=head1 DESCRIPTION

B<Hash::Wrap> creates objects from hashes, providing accessors for
hash elements.  The objects are hashes, and may be modified using the
standard Perl hash operations and the object's accessors will behave
accordingly.

Why use this class? Sometimes a hash is created on the fly and it's too
much of a hassle to build a class to encapsulate it.

  sub foo () { ... ; return { a => 1 }; }

With C<Hash::Wrap>:

  use Hash::Wrap;

  sub foo () { ... ; return wrap_hash( { a => 1 ); }

  my $obj = foo ();
  print $obj->a;

Elements can be added or removed to the object and accessors will
track them.  The object may be made immutable, or may have a restricted
set of attributes.

There are many similar modules on CPAN (see L<SEE ALSO> for comparisons).

What sets B<Hash::Wrap> apart is that it's possible to customize
object construction and accessor behavior:

=over

=item *

It's possible to use the passed hash directly, or make shallow or deep
copies of it.

=item *

Accessors can be customized so that accessing a non-existent element
can throw an exception or return the undefined value.

=item *

On recent enough versions of Perl, accessors can be lvalues, e.g.

   $obj->existing_key = $value;

=back

=head1 USAGE

=head2 Simple Usage

C<use>'ing B<Hash::Wrap> without options imports a subroutine called
B<wrap_hash> which takes a hash, blesses it into a wrapper class and
returns the hash:

  use Hash::Wrap;

  my $h = wrap_hash { a => 1 };
  print $h->a, "\n";             # prints 1

B<[API change @ v1.0]>
The passed hash must be a plain hash (i.e. not an object or blessed
hash).  To pass an object, you must specify a custom clone subroutine
returning a plain hashref via the L</-clone> option.

The wrapper class has no constructor method, so the only way to create
an object is via the B<wrap_hash> subroutine. (See L</WRAPPER CLASSES>
for more about wrapper classes) If B<wrap_hash> is called without
arguments, it will create a hash for you.

=head2 Advanced Usage

=head3 B<wrap_hash> is an awful name for the constructor subroutine

So rename it:

  use Hash::Wrap { -as => "a_much_better_name_for_wrap_hash" };

  $obj = a_much_better_name_for_wrap_hash( { a => 1 } );

=head3 The Wrapper Class name matters

If the class I<name> matters, but it'll never be instantiated
except via the imported constructor subroutine:

  use Hash::Wrap { -class => 'My::Class' };

  my $h = wrap_hash { a => 1 };
  print $h->a, "\n";             # prints 1
  $h->isa( 'My::Class' );        # returns true

or, if you want it to reflect the current package, try this:

  package Foo;
  use Hash::Wrap { -class => '-target', -as => 'wrapit' };

  my $h = wrapit { a => 1 };
  $h->isa( 'Foo::wrapit' );  # returns true

Again, the wrapper class has no constructor method, so the only way to
create an object is via the generated subroutine.

=head3 The Wrapper Class needs its own class constructor method

To generate a wrapper class which can be instantiated via its own
constructor method:

  use Hash::Wrap { -class => 'My::Class', -new => 1 };

The default B<wrap_hash> constructor subroutine is still exported, so

  $h = My::Class->new( { a => 1 } );

and

  $h = wrap_hash( { a => 1 } );

do the same thing.

To give the constructor method a different name:

  use Hash::Wrap { -class => 'My::Class',  -new => '_my_new' };

To prevent the constructor subroutine from being imported:

  use Hash::Wrap { -as => undef, -class => 'My::Class', -new => 1 };

=head3 A stand alone Wrapper Class

To create a stand alone wrapper class,

   package My::Class;

   use Hash::Wrap { -base => 1 };

   1;

And later...

   use My::Class;

   $obj = My::Class->new( \%hash );

It's possible to modify the constructor and accessors:

   package My::Class;

   use Hash::Wrap { -base => 1, -new => 'new_from_hash', -undef => 1 };

   1;

=head2 Recursive wrapping

B<Hash::Wrap> can automatically wrap nested hashes using the
L</-recurse> option.

=head3 Using the original hash

The L</-recurse> option allows mapping nested hashes onto chained
methods, e.g.

   use Hash::Wrap { -recurse => -1, -as => 'recwrap' };

   my %hash = ( a => { b => { c => 'd' } } );

   my $wrap = recwrap(\%hash);

   $wrap->a->b->c eq 'd'; # true

Along the way, B<%hash>, B<$hash{a}>, B<$hash{b}>, B<$hash{c}> are all
blessed into wrapping classes.

=head3 Copying the original hash

If L</-copy> is also specified, then the relationship between the
nested hashes in the original hash and those hashes retrieved by
wrapper methods depends upon what level in the structure has been
wrapped.  For example,

   use Hash::Wrap { -recurse => -1, -copy => 1, -as => 'copyrecwrap' };
   use Scalar::Util 'refaddr';

   my %hash = ( a => { b => { c => 'd' } } );

   my $wrap = copyrecwrap(\%hash);

   refaddr( $wrap ) != refaddr( \%hash );

Because the C<< $wrap->a >> method hasn't been called, then the B<$hash{a}> structure
has yet to be wrapped, so, using C<$wrap> as a hash,

   refaddr( $wrap->{a} ) == refaddr( $hash{a} );

However,

   # invoking $wrap->a wraps a copy of $hash{a} because of the -copy
   # attribute
   refaddr( $wrap->a ) != refaddr( $hash{a} );

   # so $wrap->{a} is no longer the same as $hash{a}:
   refaddr( $wrap->{a} ) != refaddr( $hash{a} );
   refaddr( $wrap->{a} ) == refaddr( $wrap->a );

=head3 Importing into an alternative package

Normally the constructor is installed into the package importing C<Hash::Wrap>.
The C<-into> option can change that:

   package This::Package;
   use Hash::Wrap { -into => 'Other::Package' };

will install B<Other::Package::wrap_hash>.

=head1 OPTIONS

B<Hash::Wrap> works at import time.  To modify its behavior pass it
options when it is C<use>'d:

  use Hash::Wrap { %options1 }, { %options2 }, ... ;

Multiple options hashes may be passed; each hash specifies options for
a separate constructor or class.

For example,

  use Hash::Wrap
    { -as => 'cloned', clone => 1},
    { -as => 'copied', copy => 1 };

creates two constructors, C<cloned> and C<copied> with different
behaviors.

=head2 Constructor

=over

=item C<-as> => I<subroutine name>  || C<undef> || I<scalar ref> || C<-return>

(This defaults to the string C<wrap_hash> )

If the argument is

=over

=item *

a string (but not the string C<-return>)

Import the constructor subroutine with the given name.

=item *

undefined

Do not import the constructor. This is usually only used with the
L</-new> option.

=item *

a scalar ref

Do not import the constructor. Store a reference to the constructor
into the scalar.

=item *

The string C<-return>.

Do not import the constructor. The constructor subroutine(s) will be
returned from C<Hash::Import>'s C<import> method.  This is a fairly
esoteric way of doing things:

  require Hash::Wrap;
  ( $copy, $clone ) = Hash::Wrap->import( { -as => '-return', copy => 1 },
                                          { -as => '-return', clone => 1 } );

A list is always returned, even if only one constructor is created.

=back

=item C<-copy> => I<boolean>

If true, the object will store the data in a I<shallow> copy of the
hash. By default, the object uses the hash directly.

=item C<-clone> => I<boolean> | I<coderef>

Store the data in a deep copy of the hash. if I<true>,
L<Storable/dclone> is used. If a coderef, it will be called as

   $clone = $coderef->( $hash )

C<$coderef> must return a plain hashref.

By default, the object uses the hash directly.

=item C<-lexical> => I<boolean>

On Perl v5.38 or higher, this will cause the constructor subroutine to
be installed lexically in the target package.

On Perls prior to v5.38 this causes an exception.

=item C<-immutable> => I<boolean> | I<arrayref>

If the value is I<true>, the object's attributes and values are locked
and may not be altered. Note that this locks the underlying hash.

If the value is an array reference, it specifies which attributes are
allowed, I<in addition to existing attributes>.  Attributes which are
not set when the object is created are set to C<undef>. For example,

  use Hash::Wrap { -immutable => [ qw( a b c ) ] };

  my $obj = wrap_hash( { a => 1, b => 2 } );

  ! defined( $obj->c ) == true;  # true statement.

=item C<-lockkeys> => I<boolean> | I<arrayref>

If the value is I<true>, the object's attributes are restricted to the
existing keys in the hash.  If it is an array reference, it specifies
which attributes are allowed, I<in addition to existing attributes>.
The attribute's values are not locked.  Note that this locks the
underlying hash.

=item C<-into> => I<package name>

The name of the package in which to install the constructor.  By default
it's that of the caller.

=back

=head2 Accessors

=over

=item C<-undef> => I<boolean>

Normally an attempt to use an accessor for an non-existent key will
result in an exception.  This option causes the accessor
to return C<undef> instead.  It does I<not> create an element in
the hash for the key.

=item C<-lvalue> => I<flag>

If non-zero, the accessors will be lvalue routines, e.g. they can
change the underlying hash value by assigning to them:

   $obj->attr = 3;

The hash entry I<must already exist> or this will throw an exception.

lvalue subroutines are only available on Perl version 5.16 and later.

If C<-lvalue = 1> this option will silently be ignored on earlier
versions of Perl.

If C<-lvalue = -1> this option will cause an exception on earlier
versions of Perl.

=item C<-recurse> => I<integer level>

Normally only the top level hash is wrapped in a class.  This option
specifies how many levels deep into the hash hashes should be wrapped.
For example, if

 %h = ( l => 0, a => { l => 1, b => { l => 2, c => { l => 3 } } } };

 use Hash::Wrap { -recurse => 0 };
 $h->l          # => 0
 $h->a->l       # => ERROR

 use Hash::Wrap { -recurse => 1 };
 $h->l          # => 0
 $h->a->l       # => 1
 $h->a->b->l    # => ERROR

 use Hash::Wrap { -recurse => 2 };
 $h->l          # => 0
 $h->a->l       # => 1
 $h->a->b->l    # => 2
 $h->a->b->c->l # => ERROR

For infinite recursion, set C<-recurse> to C<-1>.

Constructors built for deeper hash levels will not heed the
C<-as_scalar_ref>, C<-class>, C<-base>, or C<-as> attributes.

=back

=head2 Class

=over

=item C<-base> => I<boolean>

If true, the enclosing package is converted into a proxy wrapper
class.  This should not be used in conjunction with C<-class>.  See
L</A stand alone Wrapper Class>.

=item C<-class> => I<class name>

A class with the given name will be created and new objects will be
blessed into the specified class by the constructor subroutine.  The
new class will not have a constructor method.

If I<class name> is the string C<-target> (or, deprecated,
C<-caller>), then the class name is set to the fully qualified name of
the constructor, e.g.

  package Foo;
  use Hash::Wrap { -class => '-target', -as => 'wrap_it' };

results in a class name of C<Foo::wrap_it>.

If not specified, the class name will be constructed based upon the
options.  Do not rely upon this name to determine if an object is
wrapped by B<Hash::Wrap>.

=item C<-new> => I<boolean> | I<Perl Identifier>

Add a class constructor method.

If C<-new> is a true boolean value, the method will be called
C<new>. Otherwise C<-new> specifies the name of the method.

=back

=head3 Extra Class Methods

=over

=item C<-defined> => I<boolean> | I<Perl Identifier>

Add a method which returns true if the passed hash key is defined or
does not exist. If C<-defined> is a true boolean value, the method
will be called C<defined>. Otherwise it specifies the name of the
method. For example,

   use Hash::Wrap { -defined => 1 };
   $obj = wrap_hash( { a => 1, b => undef } );

   $obj->defined( 'a' ); # TRUE
   $obj->defined( 'b' ); # FALSE
   $obj->defined( 'c' ); # FALSE

or

   use Hash::Wrap { -defined => 'is_defined' };
   $obj = wrap_hash( { a => 1 } );
   $obj->is_defined( 'a' );

=item C<-exists> => I<boolean> | I<Perl Identifier>

Add a method which returns true if the passed hash key exists. If
C<-exists> is a boolean, the method will be called
C<exists>. Otherwise it specifies the name of the method. For example,

   use Hash::Wrap { -exists => 1 };
   $obj = wrap_hash( { a => 1 } );
   $obj->exists( 'a' );

or

   use Hash::Wrap { -exists => 'is_present' };
   $obj = wrap_hash( { a => 1 } );
   $obj->is_present( 'a' );

=item C<-predicate> => I<boolean>

This adds the more traditionally named predicate methods, such as
C<has_foo> for attribute C<foo>.  Note that this option makes any
elements which begin with C<has_> unavailable via the generated
accessors.

=item C<-methods> => { I<method name> => I<code reference>, ... }

Install the passed code references into the class with the specified
names. These override any attributes in the hash.  For example,

   use Hash::Wrap { -methods => { a => sub { 'b' } } };

   $obj = wrap_hash( { a => 'a' } );
   $obj->a;  # returns 'b'

=back

=head1 WRAPPER CLASSES

A wrapper class has the following characteristics.

=over

=item *

It has the methods C<DESTROY>, C<AUTOLOAD> and C<can>.

=item *

It will have other methods if the C<-undef> and C<-exists> options are
specified. It may have other methods if it is L<a stand alone class|/A
stand alone Wrapper Class>.

=item *

It will have a constructor if either of C<-base> or C<-new> is specified.

=back

=head2 Wrapper Class Limitations

=over

=item *

Wrapper classes have C<DESTROY>, C<can> method, and C<AUTOLOAD>
methods, which will mask hash keys with the same names.

=item *

Classes which are generated without the C<-base> or C<-new> options do
not have a class constructor method, e.g C<< Class->new() >> will
I<not> return a new object.  The only way to instantiate them is via
the constructor subroutine generated via B<Hash::Wrap>.  This allows
the underlying hash to have a C<new> attribute which would otherwise
be masked by the constructor.

=back

=head1 LIMITATIONS

=head2 Lvalue accessors

Lvalue accessors are available only on Perl 5.16 and later.

=head2 Accessors for deleted hash elements

Accessors for deleted elements are not removed.  The class's C<can>
method will return C<undef> for them, but they are still available in
the class's stash.

=head2 Wrapping immutable structures

Locked (e.g. immutable) hashes cannot be blessed into a class. This
will cause B<Hash::Wrap> to fail if it is asked to work directly
(without cloning or copying) on a locked hash or recursive wrapping is
specified and the hash contains nested locked hashes.

To create an immutable B<Hash::Wrap> object from an immutable hash,
use the L</-copy> and L</-immutable> attributes.  The L</-copy>
attribute performs a shallow copy of the hash which is then locked by
L</-immutable>.  The default L</-clone> option will not work, as it
will clone the immutability of the input hash.

Adding the L</-recurse> option will properly create an immutable
wrapped object when used on locked hashes. It does not suffer the
issue described in L</Eventual immutability in nested
structures> in L</Bugs>.

=head2 Cloning with recursion

Cloning by default uses L<Storable/dclone>, which performs a deep clone
of the passed hash. In recursive mode, the clone operation is performed at every
wrapping of a nested hash, causing some data to be repeatedly cloned.
This does not create a memory leak, but it is inefficient.  Consider
using L</-copy> instead of L</-clone> with L</-recurse>.

=head1 BUGS

=head2 Eventual immutability in nested structures

Immutability is added to mutable nested structures as they are
traversed via method calls.  This means that the hash underlying the
wrapper object is not fully immutable until all nested hashes have
been visited via methods.

For example,

  use Hash::Wrap { -immutable => 1, -recurse => -1, -as 'immutable' };

  my $wrap = immutable( { a => { b => 2 } } );
  $wrap->{a}    = 11; # expected fail: IMMUTABLE
  $wrap->{a}{b} = 22; # unexpected success: NOT IMMUTABLE
  $wrap->a;
  $wrap->{a}{b} = 33; # expected fail: IMMUTABLE; $wrap->{a} is now locked

=head1 EXAMPLES

=head2 Existing keys are not compatible with method names

If a hash key contains characters that aren't legal in method names,
there's no way to access that hash entry.  One way around this is to
use a custom clone subroutine which modifies the keys so they are
legal method names.  The user can directly insert a non-method-name
key into the C<Hash::Wrap> object after it is created, and those still
have a key that's not available via a method, but there's no cure for
that.

=head1 SEE ALSO

Here's a comparison of this module and others on CPAN.

=over

=item B<Hash::Wrap> (this module)

=over

=item * core dependencies only

=item * object tracks additions and deletions of entries in the hash

=item * optionally applies object paradigm recursively

=item * accessors may be lvalue subroutines

=item * accessing a non-existing element via an accessor
throws by default, but can optionally return C<undef>

=item * can use custom package

=item * can copy/clone existing hash. clone may be customized

=item * can add additional methods to the hash object's class

=item * optionally stores the constructor in a scalar

=item * optionally provides per-attribute predicate methods
(e.g. C<has_foo>)

=item * optionally provides methods to check an attribute existence or
whether its value is defined

=item * can create immutable objects

=back

=item L<Object::Result>

As you might expect from a DCONWAY module, this does just
about everything you'd like.  It has a very heavy set of dependencies.

=item L<Hash::AsObject>

=over

=item * core dependencies only

=item * applies object paradigm recursively

=item * accessing a non-existing element via an accessor creates it

=back

=item L<Data::AsObject>

=over

=item * moderate dependency chain (no XS?)

=item * applies object paradigm recursively

=item * accessing a non-existing element throws

=back

=item L<Class::Hash>

=over

=item * core dependencies only

=item * only applies object paradigm to top level hash

=item * can add generic accessor, mutator, and element management methods

=item * accessing a non-existing element via an accessor creates it
(not documented, but code implies it)

=item * C<can()> doesn't work

=back

=item L<Hash::Inflator>

=over

=item * core dependencies only

=item * accessing a non-existing element via an accessor returns undef

=item * applies object paradigm recursively

=back

=item L<Hash::AutoHash>

=over

=item * moderate dependency chain.  Requires XS, tied hashes

=item * applies object paradigm recursively

=item * accessing a non-existing element via an accessor creates it

=back

=item L<Hash::Objectify>

=over

=item * light dependency chain.  Requires XS.

=item * only applies object paradigm to top level hash

=item * accessing a non-existing element throws, but if an existing
element is accessed, then deleted, accessor returns undef rather than
throwing

=item * can use custom package

=back

=item L<Data::OpenStruct::Deep>

=over

=item * uses source filters

=item * applies object paradigm recursively

=back

=item L<Object::AutoAccessor>

=over

=item * light dependency chain

=item * applies object paradigm recursively

=item * accessing a non-existing element via an accessor creates it

=back

=item L<Data::Object::Autowrap>

=over

=item * core dependencies only

=item * no documentation

=back

=item L<Object::Accessor>

=over

=item * core dependencies only

=item * only applies object paradigm to top level hash

=item * accessors may be lvalue subroutines

=item * accessing a non-existing element via an accessor
returns C<undef> by default, but can optionally throw. Changing behavior
is done globally, so all objects are affected.

=item * accessors must be explicitly added.

=item * accessors may have aliases

=item * values may be validated

=item * invoking an accessor may trigger a callback

=back

=item L<Object::Adhoc>

=over

=item * minimal non-core dependencies (L<Exporter::Shiny>)

=item * uses L<Class::XSAccessor> if available

=item * only applies object paradigm to top level hash

=item * provides separate getter and predicate methods, but only
for existing keys in hash.

=item * hash keys are locked.

=item * operates directly on hash.

=back

=item L<Util::H2O>

=over

=item * has a cool name

=item * core dependencies only

=item * locks hash by default

=item * optionally recurses into the hash

=item * does not track changes to hash

=item * can destroy class

=item * can add methods

=item * can use custom package

=back

=back

=head1 SUPPORT

=head2 Bugs

Please report any bugs or feature requests to bug-hash-wrap@rt.cpan.org  or through the web interface at: L<https://rt.cpan.org/Public/Dist/Display.html?Name=Hash-Wrap>

=head2 Source

Source is available at

  https://codeberg.org/djerius/p5-Hash-Wrap

and may be cloned from

  https://codeberg.org/djerius/p5-Hash-Wrap.git

=head1 AUTHOR

Diab Jerius <djerius@cpan.org>

=head1 COPYRIGHT AND LICENSE

This software is Copyright (c) 2017 by Smithsonian Astrophysical Observatory.

This is free software, licensed under:

  The GNU General Public License, Version 3, June 2007

=cut
