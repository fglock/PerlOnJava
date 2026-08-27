#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

{
    package MroAliasOuter::Inner;
    sub inherited_method { 'outer method' }

    package MroAliasChild;
    our @ISA = 'MroAliasOuter::Inner';

    package MroAliasPeer;
    our @ISA = 'MroAliasClone::Inner';

    package main;
    no strict 'refs';
    *MroAliasClone:: = \%MroAliasOuter::;

    ok(MroAliasChild->isa('MroAliasClone::Inner'),
        'descendant of a stash alias is an isa target');
    ok(MroAliasPeer->isa('MroAliasOuter::Inner'),
        'descendant alias resolves while reading ISA');
    is(MroAliasPeer->inherited_method, 'outer method',
        'method lookup follows a descendant stash alias');
}

{
    no strict 'refs';
    *Fooo::ISA = *Baro::ISA;
    @Fooo::ISA = 'Bazo';
    sub Bazo::marker { 'original' }
    sub L::marker { 'localized' }
    is(Baro->marker, 'original',
        'shared ISA resolves before localization');
    {
        local *Fooo::ISA = ['L'];
        is(Baro->marker, 'localized',
            'localized ISA alias changes method resolution through the other name');
    }
}

{
    no strict 'refs';
    @MroMovePet::ISA = 'MroMoveTike';
    @MroMoveTike::ISA = 'MroMoveBarker';
    sub MroMoveBarker::speak { 'woof' }
    my $pet = bless [], 'MroMovePet';
    is($pet->speak, 'woof', 'method resolves before moving an ancestor stash');

    sub MroMoveDog::speak { 'hello' }
    @MroMoveDog::ISA = 'MroMoveLatrator';
    *MroMoveTike:: = delete $::{'MroMoveDog::'};
    is($pet->speak, 'hello', 'moving a deleted stash updates inherited methods');
}

{
    no strict 'refs';
    @MroAliasDeleteOne::More::ISA = 'MroAliasDeleteFour';
    sub MroAliasDeleteFour::womp { 'alive' }
    *MroAliasDeleteTwo:: = *MroAliasDeleteOne::;
    delete $::{'MroAliasDeleteOne::'};
    @MroAliasDeleteChild::ISA = 'MroAliasDeleteTwo::More';
    is(MroAliasDeleteChild->womp, 'alive',
        'stash alias retains its namespace after deleting the original name');
    delete ${'MroAliasDeleteTwo::'}{'More::'};
    is(eval { MroAliasDeleteChild->womp }, undef,
        'deleting a nested namespace through its surviving alias removes methods');
}

{
    no strict 'refs';
    sub MroRebindBar::Inner::Leaf::marker { 'preserved' }
    sub MroRebindFallback::marker { 'fallback' }
    @MroRebindChild::ISA = qw(MroRebindAlias::Inner::Leaf MroRebindFallback);
    *MroRebindAlias::Nested:: = *MroRebindBar::Inner::;
    *MroRebindAlias:: = *MroRebindBar::;
    *MroRebindBar:: = *MroRebindReplacement::;
    is(MroRebindChild->marker, 'preserved',
        'replacing a source stash preserves nested classes through its old alias');
    delete ${'MroRebindAlias::Inner::'}{'Leaf::'};
    @MroRebindChild::ISA = @MroRebindChild::ISA;
    is(MroRebindChild->marker, 'fallback',
        'refreshing ISA drops the deleted preserved nested class');
}

{
    no strict 'refs';
    @MroColonChild::ISA = 'MroColonOrgan:';
    bless [], 'MroColonOrgan:';
    *{'MroColonOrgan:::'} = *MroColonTarget::;
    ok(MroColonChild->isa('MroColonTarget'),
        'a package ending in a colon follows its three-colon glob alias');

    @MroColonChild::ISA = ':';
    bless [], ':';
    *{':::'} = *MroColonPunctuation::;
    ok(MroColonChild->isa('MroColonPunctuation'),
        'the colon package follows its three-colon glob alias');
}

done_testing;
